package com.manetevaluation.pong.views.debug;

import static java.lang.Character.toUpperCase;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.manetevaluation.pong.R;
import com.manetevaluation.pong.sync.LocalReport;
import com.manetevaluation.pong.sync.Message;
import com.manetevaluation.pong.sync.bluetooth.BluetoothSyncService;
import com.manetevaluation.pong.views.SettingsActivity;
import com.raizlabs.android.dbflow.list.FlowCursorList;
import com.raizlabs.android.dbflow.list.FlowQueryList;
import com.raizlabs.android.dbflow.sql.language.SQLite;

import com.manetevaluation.pong.storage.UnknownMessage;
import com.manetevaluation.pong.storage.UnknownMessage_Table;

import org.threeten.bp.LocalDateTime;
import org.threeten.bp.format.DateTimeFormatter;

import java.io.File;
import java.util.UUID;

public class RawMessageList extends AppCompatActivity {
    public static final String TAG = "RawMessageList";
    private static long originAddress;
    private static final byte TEST_ZERO_BITS = 16;
    private static UUID TEST_TYPE;

    private FlowQueryList<UnknownMessage> messages;
    private ArrayAdapter<UnknownMessage> adapter;
    private boolean alreadyLunched = false;
    Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_raw_message_list);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        originAddress = BluetoothSyncService.Companion.longFromMacAddress(preferences.getString(SettingsActivity.KEY_BLUETOOTH_MAC, "").toUpperCase());
        TEST_TYPE = new UUID(originAddress, originAddress);
        setTitle(R.string.raw_message_view_title);

        // TODO: Use a query list that's smarter about not copying the entire list at once
        messages = SQLite.select().from(UnknownMessage.class).orderBy(UnknownMessage_Table.payload, true).flowQueryList();
        messages.registerForContentChanges(this);
        adapter = new ArrayAdapter<UnknownMessage>(this, android.R.layout.simple_list_item_1, messages);
        messages.addOnCursorRefreshListener(new FlowCursorList.OnCursorRefreshListener<UnknownMessage>() {
            @Override
            public void onCursorRefreshed(FlowCursorList<UnknownMessage> cursorList) {
                Log.d(TAG, "Displaying new unknown message");
                adapter.notifyDataSetChanged();
                Intent intent = new Intent("com.example.ACTION_NEW_DATA");
                intent.putExtra("new_string", "Displaying new unknown message");
                sendBroadcast(intent);
            }
        });

        ListView listView = (ListView) findViewById(R.id.content_raw_message_list_view);
        listView.setAdapter(adapter);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(!alreadyLunched){
                    handler.postDelayed(runnableCode, 0);
                    alreadyLunched=true;
                }
            }
        });
    }

    private Runnable runnableCode = new Runnable() {
        long destinationNode, idMessage;
        int messageCounter=0;

        @Override
        public void run() {
            try {
                byte[] payload = "This is an unencrypted test message".getBytes();
                destinationNode = BluetoothSyncService.Companion.getRandomNode();
                TEST_TYPE = new UUID(destinationNode, originAddress);
                idMessage = Long.parseLong(originAddress / 100000 + Long.toString(messageCounter));
                UnknownMessage.Companion.rawCreateAndSignAsync(idMessage, payload, TEST_ZERO_BITS, TEST_TYPE).subscribe();
                new NewMessageWriter(getApplicationContext().getFilesDir(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), BluetoothSyncService.Companion.macAddressFromLong(originAddress), BluetoothSyncService.Companion.macAddressFromLong(destinationNode), idMessage, 0).run();
                messageCounter++;
                handler.postDelayed(this, 500);
                //handler.postDelayed(this, 5000);
                //handler.postDelayed(this, 10000);
                //handler.postDelayed(this, 30000);
                //handler.postDelayed(this, 60000);
            } catch (UnknownMessage.PayloadTooLargeException e) {
                Log.e(TAG, "Message not created", e);
            }
        }
    };

    public class NewMessageWriter extends Thread {
        private File absolutePath;
        private String _timestamp;
        private String _sourceNode;
        private String _connectedNode;
        private Long idMessage;
        private int hopNumber;

        public NewMessageWriter(File absolutePath, String _timestamp, String _sourceNode, String _connectedNode, Long idMessage, int hopNumber){
            this.absolutePath = absolutePath;
            this._timestamp = _timestamp;
            this._sourceNode = _sourceNode;
            this._connectedNode = _connectedNode;
            this.idMessage = idMessage;
            this.hopNumber = hopNumber;
        }

        public void run(){
            Log.w("NewMessageThread", "New message generated");
            LocalReport.Companion.addNewGeneratedMessage(absolutePath, new Message(_timestamp, _sourceNode, _connectedNode, _connectedNode, idMessage, hopNumber, null));
        }
    }

}
