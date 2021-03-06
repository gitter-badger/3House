package treehou.se.habit.ui;

import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.gson.Gson;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Realm;

import org.atmosphere.wasync.Client;
import org.atmosphere.wasync.ClientFactory;
import org.atmosphere.wasync.Decoder;
import org.atmosphere.wasync.Encoder;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.OptionsBuilder;
import org.atmosphere.wasync.RequestBuilder;
import org.atmosphere.wasync.Socket;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import retrofit.Callback;
import retrofit.RetrofitError;
import treehou.se.habit.R;
import treehou.se.habit.connector.Communicator;
import treehou.se.habit.connector.ConnectorUtil;
import treehou.se.habit.connector.Constants;
import treehou.se.habit.connector.GsonHelper;
import treehou.se.habit.connector.TrustModifier;
import treehou.se.habit.core.LinkedPage;
import treehou.se.habit.core.db.ServerDB;
import treehou.se.habit.core.Widget;
import treehou.se.habit.ui.widgets.WidgetFactory;
import treehou.se.habit.util.ThreadPool;

public class PageFragment extends Fragment {

    private static final String TAG = "PageFragment";
    private static final String PAGE_REQUEST_TAG = "PageRequestTag";

    // Arguments
    private static final String ARG_PAGE    = "ARG_PAGE";
    private static final String ARG_SERVER  = "ARG_SERVER";

    private static final String STATE_PAGE = "STATE_PAGE";

    private ServerDB server;
    private LinkedPage page;

    private LinearLayout louFragments;

    private WidgetFactory widgetFactory;
    private List<Widget> widgets = new ArrayList<>();
    private List<WidgetFactory.IWidgetHolder> widgetHolders = new ArrayList<>();

    private AsyncTask<Void, Void, Void> longPoller;

    private boolean initialized = false;

    private Socket pollSocket;

    /**
     * Creates a new instane of the page.
     *
     * @param server the server to connect to
     * @param page the page to visualise
     *
     * @return Fragment visualazing a page
     */
    public static PageFragment newInstance(ServerDB server, LinkedPage page) {
        Gson gson = GsonHelper.createGsonBuilder();

        Bundle args = new Bundle();
        args.putString(ARG_PAGE, gson.toJson(page));
        args.putLong(ARG_SERVER, server.getId());

        PageFragment fragment = new PageFragment();
        fragment.setArguments(args);

        return fragment;
    }

    public PageFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        Gson gson = GsonHelper.createGsonBuilder();

        long serverId = args.getLong(ARG_SERVER);
        server = ServerDB.load(ServerDB.class, serverId);

        String jPage = args.getString(ARG_PAGE);
        page = gson.fromJson(jPage, LinkedPage.class);

        initialized = false;
        if(savedInstanceState != null && savedInstanceState.containsKey(STATE_PAGE)) {
            jPage = savedInstanceState.getString(STATE_PAGE);
            LinkedPage savedPage = gson.fromJson(jPage, LinkedPage.class);
            if(savedPage.getId().equals(page.getId())) {
                page = savedPage;
                initialized = true;
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_widget, container, false);

        louFragments = (LinearLayout) view.findViewById(R.id.lou_widgets);
        updatePage(page);

        if(!initialized) {
            requestPageUpdate();
        }
        initialized = true;

        return view;
    }

    private void requestPageUpdate(){
        Communicator communicator = Communicator.instance(getActivity());
        communicator.requestPage(server, page, new Callback<LinkedPage>() {
            @Override
            public void success(final LinkedPage linkedPage, final retrofit.client.Response response) {
                //TODO update instead of reset.
                Log.d(TAG, "Received update " + linkedPage.getWidget().size() + " widgets from  " + page.getLink());
                ThreadPool.instance().submit(new Runnable() {
                    @Override
                    public void run() {
                        updatePage(linkedPage);
                    }
                });
            }

            @Override
            public void failure(RetrofitError error) {
                Log.d(TAG, "error " + error.getCause() + " " + error.getMessage());

                if(getActivity() != null) {

                    // TODO Check type of error.
                    // TODO Retry on remote server.
                    Toast.makeText(getActivity(), R.string.lost_server_connection, Toast.LENGTH_LONG).show();
                    getActivity().getSupportFragmentManager().popBackStack();
                }
            }
        });
    }

    // TODO extract to separate class
    private AsyncTask<Void, Void, Void> createLongPoller() {
        AsyncTask<Void, Void, Void> longPoller = new AsyncTask<Void, Void, Void>() {

            @Override
            protected Void doInBackground(Void... params) {


                Realm realm = null;
                if(server.requiresAuth()){
                    realm = new Realm.RealmBuilder()
                            .setPrincipal(server.getUsername())
                            .setPassword(server.getPassword())
                            .setUsePreemptiveAuth(true)
                            .setScheme(Realm.AuthScheme.BASIC)
                            .build();
                }

                AsyncHttpClient asyncHttpClient = new AsyncHttpClient(
                    new AsyncHttpClientConfig.Builder().setAcceptAnyCertificate(true)
                        .setHostnameVerifier(new TrustModifier.NullHostNameVerifier())
                        .setRealm(realm)
                        .build()
                );

                Client client = ClientFactory.getDefault().newClient();
                OptionsBuilder optBuilder = client.newOptionsBuilder().runtime(asyncHttpClient);

                UUID atmosphereId = UUID.randomUUID();

                RequestBuilder request = client.newRequestBuilder()
                    .method(org.atmosphere.wasync.Request.METHOD.GET)
                    .uri(page.getLink())
                    .header("Accept", "application/json")
                    .header("Accept-Charset", "utf-8")
                    .header("X-Atmosphere-Transport", "long-polling")
                    .header("X-Atmosphere-tracking-id", atmosphereId.toString())
                    .encoder(new Encoder<String, Reader>() {        // Stream the request body
                        @Override
                        public Reader encode(String s) {
                            Log.d(TAG, "RequestBuilder encode");
                            return new StringReader(s);
                        }
                    })
                    .decoder(new Decoder<String, LinkedPage>() {
                        @Override
                        public LinkedPage decode(Event e, String s) {
                            Gson gson = GsonHelper.createGsonBuilder();
                            return gson.fromJson(s, LinkedPage.class);
                        }
                    })
                    .transport(org.atmosphere.wasync.Request.TRANSPORT.LONG_POLLING);                    // Fallback to Long-Polling

                if (server.requiresAuth()){
                    request.header(Constants.HEADER_AUTHENTICATION, ConnectorUtil.createAuthValue(server.getUsername(), server.getPassword()));
                }

                pollSocket = client.create(optBuilder.build());
                try {
                    Log.d(TAG, "Socket " + pollSocket + " " + request.uri());
                    pollSocket.on(new Function<LinkedPage>() {
                        @Override
                        public void on(LinkedPage page) {
                            Log.d(TAG, "Socket received");
                            updatePage(page);
                        }
                    })
                    .open(request.build());
                } catch (IOException | ExceptionInInitializerError e) {
                    Log.d(TAG, "Got error " + e);
                }

                Log.d(TAG,"Poller started");

                return null;
            }

            @Override
            protected void onCancelled() {
                super.onCancelled();

                if(pollSocket != null) {
                    pollSocket.close();
                }
            }
        };
        return longPoller;
    }


    @Override
    public void onResume() {
        super.onResume();

        // Start listening for server updates
        // TODO Support for older versions.
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            longPoller = createLongPoller();
            longPoller.execute();
        }
    }

    /**
     * Update page.
     *
     * Recreate all widgets needed.
     *
     * @param page
     */
    private synchronized void updatePage(final LinkedPage page){
        Log.d(TAG, "Updating page " + page.getTitle() + " widgets " + widgets.size() + " : " + page.getWidget().size());
        this.page = page;
        widgetFactory = new WidgetFactory(getActivity(), server, page);

        final List<Widget> pageWidgets = page.getWidget();
        boolean invalidate = pageWidgets.size() != widgets.size();
        if(!invalidate){
            for(int i=0; i < widgets.size(); i++) {
                Widget currentWidget = widgets.get(i);
                Widget newWidget = pageWidgets.get(i);

                if(currentWidget.needUpdate(newWidget)){
                    Log.d(TAG, "Widget " + currentWidget.getType() + " " + currentWidget.getLabel() + " needs update");
                    invalidate = true;
                    break;
                }
            }
        }

        final boolean invalidateWidgets = invalidate;
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(invalidateWidgets) {
                    Log.d(TAG, "Invalidating widgets " + pageWidgets.size() + " : " + widgets.size() + " " + page.getTitle());

                    widgetHolders.clear();
                    louFragments.removeAllViews();

                    for (Widget widget : pageWidgets) {
                        try {
                            WidgetFactory.IWidgetHolder result = widgetFactory.createWidget(widget, null);
                            widgetHolders.add(result);
                            louFragments.addView(result.getView());
                        } catch (Exception e) {
                            Log.w(TAG, "Create widget failed", e);
                        }
                    }
                    widgets.clear();
                    widgets.addAll(pageWidgets);
                }
                else {
                    Log.d(TAG, "updating widgets");
                    for (int i=0; i < widgetHolders.size(); i++) {

                        try {
                            WidgetFactory.IWidgetHolder holder = widgetHolders.get(i);

                            Log.d(TAG, "updating widget " + holder.getClass().getSimpleName());
                            Widget newWidget = pageWidgets.get(i);

                            holder.update(newWidget);
                        } catch (Exception e) {
                            Log.w(TAG, "Updating widget failed", e);
                        }
                    }
                }
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();

        // Stop listening for server updates
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && longPoller != null && pollSocket != null) {
            pollSocket.close();
            longPoller.cancel(true);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putSerializable(STATE_PAGE, GsonHelper.createGsonBuilder().toJson(page)); // TODO save state prevent fragment from requesting state again
        super.onSaveInstanceState(outState);
    }
}
