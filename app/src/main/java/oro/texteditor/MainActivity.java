package oro.texteditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Text ORO: открыть, править, копировать, вставить, сохранить текстовый файл. */
public class MainActivity extends Activity {

    static final int GREEN = 0xFF3B6D11, GRAY = 0xFF5F5E5A, PAGE = 0xFFF4F3EF;
    static final int REQ_OPEN = 1, REQ_SAVE_AS = 2;
    static final long MAX_BYTES = 5L * 1024 * 1024;

    EditText edit;
    TextView title;
    Uri uri;              // текущий файл (null — новый, ещё не сохранённый)
    String name = "Без имени";
    boolean modified;
    boolean loading;
    Runnable afterSave;   // что сделать после «Сохранить» в вопросе о несохранённом

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        // верхняя панель
        LinearLayout top = bar();
        top.addView(btn("Открыть", v -> guard(this::pickFile)));
        top.addView(btn("Сохранить", v -> save(null)));
        top.addView(btn("Сохранить как", v -> saveAs(null)));
        root.addView(top);

        // имя файла
        title = new TextView(this);
        title.setTextSize(14);
        title.setTextColor(GRAY);
        title.setPadding(dp(14), dp(6), dp(14), dp(6));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        root.addView(title);

        // поле текста
        edit = new EditText(this);
        edit.setBackgroundColor(Color.WHITE);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setTextSize(16);
        edit.setTextColor(0xFF2C2C2A);
        edit.setPadding(dp(14), dp(10), dp(14), dp(10));
        edit.setHorizontallyScrolling(false);
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable e) {
                if (!loading && !modified) { modified = true; refreshTitle(); }
            }
        });
        root.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // нижняя панель
        LinearLayout bottom = bar();
        bottom.addView(btn("Копировать", v -> copy()));
        bottom.addView(btn("Вставить", v -> paste()));
        bottom.addView(btn("Всё", v -> { edit.requestFocus(); edit.selectAll(); }));
        root.addView(bottom);

        setContentView(root);
        refreshTitle();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    /** Файл, открытый из файлового менеджера («Открыть с помощью»). */
    void handleIntent(Intent in) {
        if (in == null || in.getData() == null) return;
        String a = in.getAction();
        if (!Intent.ACTION_VIEW.equals(a) && !Intent.ACTION_EDIT.equals(a)) return;
        final Uri u = in.getData();
        in.setData(null);
        guard(() -> load(u));
    }

    // ---------- открыть ----------
    void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_OPEN);
    }

    void load(Uri u) {
        try (InputStream is = getContentResolver().openInputStream(u)) {
            if (is == null) throw new Exception("нет доступа");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BYTES) { toast("Файл больше 5 МБ — не открываю"); return; }
            }
            byte[] data = bo.toByteArray();
            int off = (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                    && (data[2] & 0xFF) == 0xBF) ? 3 : 0; // BOM
            loading = true;
            edit.setText(new String(data, off, data.length - off, StandardCharsets.UTF_8));
            loading = false;
            uri = u;
            name = displayName(u);
            modified = false;
            refreshTitle();
        } catch (Exception e) {
            loading = false;
            toast("Не удалось открыть файл: " + e.getMessage());
        }
    }

    // ---------- сохранить ----------
    /** @param then что сделать после успешного сохранения (может быть null) */
    void save(Runnable then) {
        if (uri == null) { saveAs(then); return; }
        if (write(uri)) {
            modified = false;
            refreshTitle();
            toast("Сохранено");
            if (then != null) then.run();
        } else {
            // нет права записи в этот файл — предложим «Сохранить как»
            saveAs(then);
        }
    }

    void saveAs(Runnable then) {
        afterSave = then;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        String nm = name.equals("Без имени") ? "заметка.txt" : name;
        i.putExtra(Intent.EXTRA_TITLE, nm);
        startActivityForResult(i, REQ_SAVE_AS);
    }

    boolean write(Uri u) {
        try (OutputStream os = getContentResolver().openOutputStream(u, "wt")) {
            if (os == null) return false;
            os.write(edit.getText().toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) { afterSave = null; return; }
        Uri u = data.getData();
        if (req == REQ_OPEN) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
            load(u);
        } else if (req == REQ_SAVE_AS) {
            if (write(u)) {
                uri = u;
                name = displayName(u);
                modified = false;
                refreshTitle();
                toast("Сохранено");
                Runnable r = afterSave;
                afterSave = null;
                if (r != null) r.run();
            } else {
                afterSave = null;
                toast("Не удалось сохранить файл");
            }
        }
    }

    // ---------- копировать / вставить ----------
    void copy() {
        int s = edit.getSelectionStart(), e = edit.getSelectionEnd();
        CharSequence t = edit.getText();
        CharSequence part = (s >= 0 && e > s) ? t.subSequence(s, e) : t;
        if (part.length() == 0) { toast("Нечего копировать"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("text", part));
        toast(s != e ? "Выделенное скопировано" : "Весь текст скопирован");
    }

    void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) { toast("Буфер обмена пуст"); return; }
        CharSequence t = cd.getItemAt(0).coerceToText(this);
        if (t == null || t.length() == 0) { toast("Буфер обмена пуст"); return; }
        int s = Math.max(edit.getSelectionStart(), 0), e = Math.max(edit.getSelectionEnd(), 0);
        edit.getText().replace(Math.min(s, e), Math.max(s, e), t);
        edit.requestFocus();
    }

    // ---------- несохранённые правки ----------
    /** Выполнить действие; если есть несохранённые правки — сначала спросить. */
    void guard(Runnable action) {
        if (!modified) { action.run(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Сохранить изменения?")
                .setMessage(name)
                .setPositiveButton("Сохранить", (d, w) -> save(action))
                .setNegativeButton("Не сохранять", (d, w) -> { modified = false; action.run(); })
                .setNeutralButton("Отмена", null)
                .show();
    }

    @Override public void onBackPressed() {
        guard(super::onBackPressed);
    }

    // ---------- вспомогательное ----------
    String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) { }
        String p = u.getLastPathSegment();
        return p == null ? "Без имени" : p.substring(p.lastIndexOf('/') + 1);
    }

    void refreshTitle() { title.setText(name + (modified ? "  ●" : "")); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    LinearLayout bar() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(6), dp(6), dp(6), dp(6));
        l.setBackgroundColor(Color.WHITE);
        return l;
    }

    Button btn(String s, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(GREEN);
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
