package de.wavegate.tos.lockscreennotes.activity;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.wavegate.tos.lockscreennotes.R;
import de.wavegate.tos.lockscreennotes.activity.EditNoteActivity;
import de.wavegate.tos.lockscreennotes.activity.NotesActivity;
import de.wavegate.tos.lockscreennotes.activity.SettingsActivity;
import de.wavegate.tos.lockscreennotes.data.Note;
import de.wavegate.tos.lockscreennotes.data.NoteAdapter;
import de.wavegate.tos.lockscreennotes.data.RelativeTimeTextfieldContainer;
import de.wavegate.tos.lockscreennotes.data.font.FontAwesomeDrawableBuilder;
import de.wavegate.tos.lockscreennotes.sql.DBAdapter;
import de.wavegate.tos.lockscreennotes.util.NotesNotificationManager;
import timber.log.Timber;

public class MainActivity extends NotesActivity implements Observer {

	public static final int ONE_SECOND_IN_MS = 1000;
	public static final String LOGTAG = "LockScreenNotes";
	public static final String PREFS_HIDE_TUTORIAL = "prefs_hide_tutorial";

	private DBAdapter databaseAdapter;
	private NoteAdapter noteAdapter;
	private ScrollView tutorialView;
	private ListView notesList;
	private CheckBox tutorialDontShowAgainCB;
	private TextView nothingToDisplayLB;
	private ExecutorService executorService;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		//Log.i(LOGTAG, "====== Home Screen Notes onCreate() ======");

		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		notesList = (ListView) findViewById(R.id.notes_list);
		tutorialView = (ScrollView) findViewById(R.id.tutorial_view);
		nothingToDisplayLB = (TextView) findViewById(R.id.nothing_to_display);

		tutorialDontShowAgainCB = (CheckBox) findViewById(R.id.tutorial_dont_show_again_cb);
		tutorialDontShowAgainCB.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				onTutorialCBclicked(isChecked);
			}
		});

		new Date();

		//tutorialDontShowAgainCB.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		databaseAdapter = new DBAdapter(this);
		databaseAdapter.open();
		databaseAdapter.addObserver(this);

		//Log.i(LOGTAG, "Database opened.");

		noteAdapter = new NoteAdapter(this, R.layout.note_row, new ArrayList<Note>());
		notesList.setAdapter(noteAdapter);
		//notesList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
		//	@Override
		//	public boolean onItemLongClick(AdapterView<?> adapterView, View view, int i, long l) {
		//		Log.i(LOGTAG, "Long click on Note-Item.");
		//		return false;
		//	}
		//});

		PreferenceManager.setDefaultValues(this, R.xml.prefs_general, false);

		setShowNotifications(true);
		loadNotesFromDB();
		setupRelativeDateUpdater();

		FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
		fab.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				onFABClicked();
			}
		});
		fab.setImageDrawable(FontAwesomeDrawableBuilder.get(this,R.string.fa_icon_plus,48, Color.WHITE));

		if (notesList.getCount() == 0)
			tutorialView.animate().alpha(1f).setDuration(2000);
	}

	private void onTutorialCBclicked(boolean isChecked) {
		SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
		editor.putBoolean(PREFS_HIDE_TUTORIAL, isChecked);
		//Log.i(LOGTAG, "Changed the 'dont show tutorial again' prefs: " + isChecked);
		editor.apply();
	}

	private void loadNotesFromDB() {
		noteAdapter.clear();
		//Cursor cursor = databaseAdapter.getAllRows();

		//Log.i(LOGTAG, "Refreshing displayed Notes. Found: " + cursor.getCount());
		ArrayList<Note> list = Note.getAllNotesFromDB(databaseAdapter);

		Collections.sort(list);
		for (Note n : list) noteAdapter.add(n);

		noteAdapter.notifyDataSetChanged();
		updateTutorialView();
	}

	private void saveNotesToDB() {
		ArrayList<Note> notes = new ArrayList<>();
		for (int i = 0; i < noteAdapter.getCount(); i++) notes.add(noteAdapter.getItem(i));

		boolean changed = false;
		//Log.i(LOGTAG, "Saving known Notes. Found: " + notes.size());
		for (Note note : notes) {
			long id = note.getDatabaseID();
			String text = note.getText();
			int enabled = note.isEnabledSQL();
			long timestamp = note.getTimestamp();

			changed |= databaseAdapter.updateRow(id, text, enabled, timestamp);
		}

		//Log.i(LOGTAG, "Saving finished. Changed? " + changed);
	}

	public void requestDeleteNote(final Note note) {
		//Log.i(LOGTAG, "Request to delete note: '" + note.getText() + "'");
		new AlertDialog.Builder(this)
				.setTitle(R.string.delete_dialog_title)
				.setMessage(String.format(getString(R.string.delete_dialog_content), note.getTextPreview()))
				.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						deleteNote(note.getDatabaseID());
					}
				})
				.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
					}
				})
				.setIcon(android.R.drawable.ic_dialog_alert)
				.show();
	}

	public void requestEditNote(Note note) {
		setShowNotifications(false);

		Intent intent = new Intent(this, EditNoteActivity.class);
		intent.putExtra(EditNoteActivity.NOTE_ACTIVITY_NOTE_ID, note.getDatabaseID());
		startActivity(intent);
	}

	private void requestDeleteAll() {
		new AlertDialog.Builder(this)
				.setTitle(R.string.deleteall_dialog_title)
				.setMessage(R.string.deleteall_dialog_text)
				.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						for (Note n : Note.getAllNotesFromDB(databaseAdapter)) {
							deleteNote(n.getDatabaseID());
						}
					}
				})
				.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
					}
				})
				.setIcon(android.R.drawable.ic_dialog_alert)
				.show();
	}

	private void requestDisableAll() {
		for (int i = 0; i < noteAdapter.getCount(); i++) {
			Note n = noteAdapter.getItem(i);
			if (n != null) {
				n.setEnabled(false);
			}
		}

		saveNotesToDB();
		noteAdapter.notifyDataSetChanged();
	}

	private void deleteNote(long id) {
		databaseAdapter.deleteRow(id);    //we can request changes to the database here, and this object listens to changes and updates
	}

	@Override
	protected void onPause() {
		super.onPause();
		databaseAdapter.deleteObserver(this);
		saveNotesToDB();
		databaseAdapter.close();

		if (executorService != null) {
			executorService.shutdownNow();
		}

		//Log.i(LOGTAG, "Mainactivity: onPause() [Show notifications? " + isShowNotifications() + "]");
		if (isShowNotifications())
			new NotesNotificationManager(this).showNotifications();
	}

	@Override
	protected void onResume() {
		super.onResume();
		databaseAdapter.addObserver(this);
		databaseAdapter.open();
		loadNotesFromDB();

		tutorialDontShowAgainCB.setChecked(PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREFS_HIDE_TUTORIAL, false));
		setupRelativeDateUpdater();

		//Log.i(LOGTAG, "Mainactivity: onResume()");
		new NotesNotificationManager(this).hideNotifications();
	}

	private void updateTutorialView() {
		boolean prefs_hide = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREFS_HIDE_TUTORIAL, false);
		boolean tut = noteAdapter.getCount() == 0;
		tutorialView.setVisibility(View.GONE);
		nothingToDisplayLB.setVisibility(View.GONE);
		notesList.setVisibility(View.VISIBLE);
		//Log.i(LOGTAG, "Checking data for displaying the tutorial now. Empty? " + tut + " Preferences 'HIDE'? " + prefs_hide);
		if (tut) {
			notesList.setVisibility(View.GONE);
			if (prefs_hide) {
				nothingToDisplayLB.setVisibility(View.VISIBLE);
			} else {
				tutorialView.setVisibility(View.VISIBLE);
			}
		}
	}

	private void setupRelativeDateUpdater() {
		SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
		if (!sharedPref.getBoolean("prefs_time_relative", false)) {
			executorService = null;
			return;
		}

		if (executorService != null) {
			executorService.shutdownNow();
		}

		executorService = Executors.newFixedThreadPool(1);
		executorService.execute(new Runnable() {
			@Override
			public void run() {
				try {
					while (!executorService.isShutdown()) {
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								RelativeTimeTextfieldContainer container = RelativeTimeTextfieldContainer.getContainer();
								container.updateText(MainActivity.this);
							}
						});
						Thread.sleep(ONE_SECOND_IN_MS);
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
					Timber.e(e, "Loop interrupted!");
				}

			}
		});
	}

	private void onFABClicked() {
		saveNotesToDB();
		addNewNote();
	}

	public void addNewNote() {
		addNewNote("");
	}

	public void addNewNote(String text) {
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		boolean enabled = sharedPreferences.getBoolean("prefs_auto_enable_new_notes", true);
		Note note = new Note(text, enabled, new Date().getTime());
		long id = databaseAdapter.insertRow(note.getText(), note.isEnabledSQL(), note.getTimestamp());
		note.setDatabaseID(id);

		requestEditNote(note);
	}

	@Override
	public void setShowNotifications(boolean showNotifications) {
		//boolean b = isShowNotifications();
		super.setShowNotifications(showNotifications);

		//Log.i(LOGTAG, "Mainactivity changed its ShowNotification Behavior: " + b + " -> " + isShowNotifications());
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		databaseAdapter.close();

		//Log.i(LOGTAG, "Database closed.");
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.menu_main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();

		//noinspection SimplifiableIfStatement
		switch (id) {
			case R.id.action_settings:
				setShowNotifications(false);
				Intent intent = new Intent(this, SettingsActivity.class);
				startActivity(intent);
				return true;
			case R.id.action_delete_all:
				requestDeleteAll();
				return true;

			case R.id.action_disable_all:
				requestDisableAll();
				return true;
		}


		return super.onOptionsItemSelected(item);
	}

	@Override
	public void update(Observable observable, Object o) {
		//Log.i(LOGTAG, "MainActivity is triggered!");
		loadNotesFromDB();
	}
}