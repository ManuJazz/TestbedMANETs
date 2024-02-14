package com.manetevaluation.pong.views;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.manetevaluation.pong.R;
import com.manetevaluation.pong.models.LocalIdentity;
import com.manetevaluation.pong.views.debug.RawMessageList;
import com.raizlabs.android.dbflow.sql.language.SQLite;

import java.util.ArrayList;
import java.util.List;

public class ConversationList extends AppCompatActivity {
    public static final String TAG = "ConversationList";
    public ArrayAdapter<String> adapter;
    private List<String> logmessages;

    private final BroadcastReceiver dataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Asegúrate de que esto se ejecute en el hilo principal
            if ("com.example.ACTION_NEW_DATA".equals(intent.getAction())) {
                String newData = intent.getStringExtra("new_string");
                if (newData != null) {
                    logmessages.add(newData);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("com.example.ACTION_DATA_UPDATED");
        registerReceiver(dataUpdateReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(dataUpdateReceiver);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation_list);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        setTitle(R.string.conversation_view_title);

        this.logmessages = new ArrayList();

        ListView listView = (ListView) findViewById(R.id.RawlogList);
        this.adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, logmessages);
        listView.setAdapter(adapter);
        this.logmessages.add("Monitoring network...");
        adapter.notifyDataSetChanged();

        IntentFilter filter = new IntentFilter("com.example.ACTION_NEW_DATA");
        registerReceiver(dataUpdateReceiver, filter);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        if (SQLite.selectCountOf().from(LocalIdentity.class).count() < 1) {
            // TODO: Prevent the back button from bringing us back to the ConversationList until there's an identity
            Log.d(TAG, "No identities exist, creating a new one");
            startActivity(new Intent(this, NewIdentityActivity.class));
        }
    }

    // TODO: Implement conversations here

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_conversation_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // TODO: Hide the debug-oriented options in release builds
        switch (item.getItemId()) {
            case R.id.action_raw_message_list:
                startActivity(new Intent(this, RawMessageList.class));
                return true;
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }


}
