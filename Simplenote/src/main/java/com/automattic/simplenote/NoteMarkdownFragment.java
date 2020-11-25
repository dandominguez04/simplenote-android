package com.automattic.simplenote;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.core.view.MenuCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.automattic.simplenote.analytics.AnalyticsTracker;
import com.automattic.simplenote.models.Note;
import com.automattic.simplenote.utils.AppLog;
import com.automattic.simplenote.utils.AppLog.Type;
import com.automattic.simplenote.utils.BrowserUtils;
import com.automattic.simplenote.utils.ContextUtils;
import com.automattic.simplenote.utils.DrawableUtils;
import com.automattic.simplenote.utils.NetworkUtils;
import com.automattic.simplenote.utils.NoteUtils;
import com.automattic.simplenote.utils.SimplenoteLinkify;
import com.automattic.simplenote.utils.ThemeUtils;
import com.commonsware.cwac.anddown.AndDown;
import com.google.android.material.snackbar.Snackbar;
import com.simperium.client.Bucket;
import com.simperium.client.BucketObjectMissingException;

import java.lang.ref.SoftReference;

import static com.automattic.simplenote.utils.SimplenoteLinkify.SIMPLENOTE_LINK_PREFIX;

public class NoteMarkdownFragment extends Fragment implements Bucket.Listener<Note> {
    public static final String ARG_ITEM_ID = "item_id";

    private Bucket<Note> mNotesBucket;
    private Note mNote;
    private String mCss;
    private WebView mMarkdown;
    private boolean mIsLoadingNote;

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.note_markdown, menu);
        MenuCompat.setGroupDividerEnabled(menu, true);

        DrawableUtils.tintMenuWithAttribute(
            requireContext(),
            menu,
            R.attr.toolbarIconColor
        );

        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            DrawableUtils.setMenuItemAlpha(item, 0.3);  // 0.3 is 30% opacity.
        }

        if (mNote != null) {
            MenuItem viewPublishedNoteItem = menu.findItem(R.id.menu_info);
            viewPublishedNoteItem.setVisible(true);
            MenuItem trashItem = menu.findItem(R.id.menu_trash);

            if (mNote.isDeleted()) {
                trashItem.setTitle(R.string.restore);
            } else {
                trashItem.setTitle(R.string.trash);
            }

            DrawableUtils.tintMenuItemWithAttribute(getActivity(), trashItem, R.attr.toolbarIconColor);
        }

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        AppLog.add(Type.SCREEN, "Created (NoteMarkdownFragment)");
        mNotesBucket = ((Simplenote) requireActivity().getApplication()).getNotesBucket();

        // Load note if we were passed an ID.
        Bundle arguments = getArguments();

        if (arguments != null && arguments.containsKey(ARG_ITEM_ID)) {
            String key = arguments.getString(ARG_ITEM_ID);
            new LoadNoteTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, key);
        }

        setHasOptionsMenu(true);
        View layout;

        if (BrowserUtils.isWebViewInstalled(requireContext())) {
            layout = inflater.inflate(R.layout.fragment_note_markdown, container, false);
            mMarkdown = layout.findViewById(R.id.markdown);
            mMarkdown.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){
                        String url = request.getUrl().toString();

                        if (url.startsWith(SimplenoteLinkify.SIMPLENOTE_LINK_PREFIX)){
                            SimplenoteLinkify.openNote(requireActivity(), url.replace(SIMPLENOTE_LINK_PREFIX, ""));
                        } else {
                            BrowserUtils.launchBrowserOrShowError(requireContext(), url);
                        }

                        return true;
                    }
                }
            );
            mCss = ThemeUtils.isLightTheme(requireContext())
                ? ContextUtils.readCssFile(requireContext(), "light.css")
                : ContextUtils.readCssFile(requireContext(), "dark.css");
        } else {
            layout = inflater.inflate(R.layout.fragment_note_error, container, false);
            layout.findViewById(R.id.error).setVisibility(View.VISIBLE);
            layout.findViewById(R.id.button).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        BrowserUtils.launchBrowserOrShowError(requireContext(), BrowserUtils.URL_WEB_VIEW);
                    }
                }
            );
        }

        return layout;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                if (!isAdded()) {
                    return false;
                }

                requireActivity().finish();
                return true;
            case R.id.menu_trash:
                if (!isAdded()) {
                    return false;
                }

                deleteNote();
                return true;
            case R.id.menu_copy_internal:
                AnalyticsTracker.track(
                    AnalyticsTracker.Stat.INTERNOTE_LINK_COPIED,
                    AnalyticsTracker.CATEGORY_LINK,
                    "internote_link_copied_markdown"
                );

                if (!isAdded()) {
                    return false;
                }

                if (BrowserUtils.copyToClipboard(requireContext(), SimplenoteLinkify.getNoteLinkWithTitle(mNote.getTitle(), mNote.getSimperiumKey()))) {
                    Snackbar.make(mMarkdown, R.string.link_copied, Snackbar.LENGTH_SHORT).show();
                } else {
                    Snackbar.make(mMarkdown, R.string.link_copied_failure, Snackbar.LENGTH_SHORT).show();
                }

                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void deleteNote() {
        NoteUtils.deleteNote(mNote, getActivity());
        requireActivity().finish();
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        // Disable trash action until note is loaded.
        menu.findItem(R.id.menu_trash).setEnabled(!mIsLoadingNote);

        MenuItem pinItem = menu.findItem(R.id.menu_pin);
        MenuItem publishItem = menu.findItem(R.id.menu_publish);
        MenuItem copyLinkItem = menu.findItem(R.id.menu_copy);
        MenuItem markdownItem = menu.findItem(R.id.menu_markdown);
        MenuItem copyLinkInternalItem = menu.findItem(R.id.menu_copy_internal);

        if (mNote != null) {
            pinItem.setChecked(mNote.isPinned());
            publishItem.setChecked(mNote.isPublished());
            markdownItem.setChecked(mNote.isMarkdownEnabled());
        }

        pinItem.setEnabled(false);
        publishItem.setEnabled(false);
        copyLinkItem.setEnabled(false);
        markdownItem.setEnabled(false);
        copyLinkInternalItem.setEnabled(true);

        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mNotesBucket.removeListener(this);
        AppLog.add(Type.SYNC, "Removed note bucket listener (NoteMarkdownFragment)");
        AppLog.add(Type.SCREEN, "Destroyed (NoteMarkdownFragment)");
    }

    @Override
    public void onResume() {
        super.onResume();
        checkWebView();
        mNotesBucket.addListener(this);
        AppLog.add(Type.SYNC, "Added note bucket listener (NoteMarkdownFragment)");
        AppLog.add(Type.NETWORK, NetworkUtils.getNetworkInfo(requireContext()));
        AppLog.add(Type.SCREEN, "Resumed (NoteMarkdownFragment)");
    }

    @Override
    public void onBeforeUpdateObject(Bucket<Note> bucket, Note note) {
    }

    @Override
    public void onDeleteObject(Bucket<Note> bucket, Note note) {
    }

    @Override
    public void onNetworkChange(Bucket<Note> bucket, Bucket.ChangeType type, String key) {
    }

    @Override
    public void onSaveObject(Bucket<Note> bucket, Note note) {
        if (note.equals(mNote)) {
            mNote = note;
            requireActivity().invalidateOptionsMenu();
        }

        AppLog.add(
            Type.SYNC,
            "Saved note callback in NoteMarkdownFragment (ID: " + note.getSimperiumKey() +
                " / Title: " + note.getTitle() +
                " / Characters: " + NoteUtils.getCharactersCount(note.getContent()) +
                " / Words: " + NoteUtils.getWordCount(note.getContent()) + ")"
        );
    }

    private void checkWebView() {
        // When a WebView is installed and mMarkdown is null, a WebView was not installed when the
        // fragment was created.  So, open the note again to show the markdown preview.
        if (BrowserUtils.isWebViewInstalled(requireContext()) && mMarkdown == null) {
            SimplenoteLinkify.openNote(requireActivity(), mNote.getSimperiumKey());
        }
    }

    public void updateMarkdown(String text) {
        if (mMarkdown != null) {
            mMarkdown.loadDataWithBaseURL(null, getMarkdownFormattedContent(mCss, text), "text/html", "utf-8", null);
        }
    }

    public static String getMarkdownFormattedContent(String cssContent, String sourceContent) {
        String header = "<html><head>" +
                "<link href=\"https://fonts.googleapis.com/css?family=Noto+Serif\" rel=\"stylesheet\">" +
                cssContent + "</head><body>";

        String parsedMarkdown = new AndDown().markdownToHtml(
                sourceContent,
                AndDown.HOEDOWN_EXT_STRIKETHROUGH | AndDown.HOEDOWN_EXT_FENCED_CODE |
                        AndDown.HOEDOWN_EXT_QUOTE | AndDown.HOEDOWN_EXT_TABLES,
                AndDown.HOEDOWN_HTML_ESCAPE
        );

        // Set auto alignment for lists, tables, and quotes based on language of start.
        parsedMarkdown = parsedMarkdown
                .replaceAll("<ol>", "<ol dir=\"auto\">")
                .replaceAll("<ul>", "<ul dir=\"auto\">")
                .replaceAll("<table>", "<table dir=\"auto\">")
                .replaceAll("<blockquote>", "<blockquote dir=\"auto\">");

        return header + "<div class=\"note-detail-markdown\">" + parsedMarkdown +
                "</div></body></html>";
    }

    private static class LoadNoteTask extends AsyncTask<String, Void, Void> {
        private SoftReference<NoteMarkdownFragment> mNoteMarkdownFragmentReference;

        private LoadNoteTask(NoteMarkdownFragment context) {
            mNoteMarkdownFragmentReference = new SoftReference<>(context);
        }

        @Override
        protected void onPreExecute() {
            NoteMarkdownFragment fragment = mNoteMarkdownFragmentReference.get();
            fragment.mIsLoadingNote = true;
        }

        @Override
        protected Void doInBackground(String... args) {
            NoteMarkdownFragment fragment = mNoteMarkdownFragmentReference.get();
            FragmentActivity activity = fragment.getActivity();

            if (activity == null) {
                return null;
            }

            String noteID = args[0];
            Simplenote application = (Simplenote) activity.getApplication();
            Bucket<Note> notesBucket = application.getNotesBucket();

            try {
                fragment.mNote = notesBucket.get(noteID);
            } catch (BucketObjectMissingException exception) {
                // TODO: Handle a missing note
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void nada) {
            NoteMarkdownFragment fragment = mNoteMarkdownFragmentReference.get();
            fragment.mIsLoadingNote = false;
            fragment.requireActivity().invalidateOptionsMenu();
        }
    }
}
