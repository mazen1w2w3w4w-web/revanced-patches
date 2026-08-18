package app.revanced.extension.youtube.patches.spans;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.patches.spans.Filter;
import app.revanced.extension.shared.patches.spans.SpanType;
import app.revanced.extension.shared.patches.spans.StringFilterGroup;
import app.revanced.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
public final class SanitizeVideoSubtitleFilter extends Filter {

    private static final String PREFS_NAME = "RVX_AiSubtitles_Prefs";
    private static final String KEY_GEMINI = "gemini_api_key";
    private static final String KEY_GROQ = "groq_api_key";
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z0-9'-]+");
    
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static View floatingAiButton = null;

    public SanitizeVideoSubtitleFilter() {
        addCallbacks(
                new StringFilterGroup(
                        Settings.SANITIZE_VIDEO_SUBTITLE,
                        "|video_subtitle."
                )
        );
    }

    @Override
    public boolean skip(String conversionContext, SpannableString spannableString, Object span,
                        int start, int end, int flags, boolean isWord, SpanType spanType, StringFilterGroup matchedGroup) {

        if (isWord) {
            if (spanType == SpanType.IMAGE) {
                hideImageSpan(spannableString, start, end, flags);
                return true;
            } else if (spanType == SpanType.CUSTOM_CHARACTER_STYLE) {
                hideSpan(spannableString, start, end, flags);
                return true;
            }
        }

        if (spannableString != null && spannableString.length() > 0) {
            applyInteractiveSubtitleEngine(spannableString);
        }

        return false;
    }

    /**
     * تحويل الكلمات إلى روابط تفاعلية + حقن زر الـ AI في مشغل الفيديو
     */
    private void applyInteractiveSubtitleEngine(SpannableString spannableString) {
        String fullText = spannableString.toString();

        ClickableSpan[] existingSpans = spannableString.getSpans(0, spannableString.length(), ClickableSpan.class);
        if (existingSpans != null && existingSpans.length > 0) return;

        Matcher matcher = WORD_PATTERN.matcher(fullText);
        while (matcher.find()) {
            final int matchStart = matcher.start();
            final int matchEnd = matcher.end();
            final String word = matcher.group();

            if (word.length() == 1 && !word.equalsIgnoreCase("a") && !word.equalsIgnoreCase("i")) {
                continue;
            }

            ClickableSpan clickableSpan = new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    if (widget instanceof TextView) {
                        TextView tv = (TextView) widget;
                        tv.setMovementMethod(LinkMovementMethod.getInstance());
                        tv.setHighlightColor(Color.TRANSPARENT);
                    }
                    // حقن الزر في واجهة المشغل أسفل اليمين إذا لم يكن موجوداً
                    attachAiButtonToPlayer(widget);
                    // فتح نافذة الترجمة الفورية والذكاء الاصطناعي
                    showLearningDialog(widget.getContext(), word, fullText);
                }

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setUnderlineText(false);
                    ds.setColor(Color.parseColor("#FFD54F")); // لون أصفر ذهبي واضح ومريح
                }
            };

            spannableString.setSpan(clickableSpan, matchStart, matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /**
     * زر عائم تفاعلي يظهر أسفل يمين واجهة مشغل الفيديو (Player Overlay Controls)
     */
    private static void attachAiButtonToPlayer(View view) {
        try {
            ViewGroup root = (ViewGroup) view.getRootView();
            if (root == null || floatingAiButton != null) return;

            mainHandler.post(() -> {
                Context ctx = view.getContext();
                Button aiBtn = new Button(ctx);
                aiBtn.setText("🤖 AI Sub");
                aiBtn.setTextSize(11f);
                aiBtn.setTextColor(Color.WHITE);
                aiBtn.setBackgroundColor(Color.parseColor("#CC1E1E2E"));
                aiBtn.setPadding(20, 10, 20, 10);

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                // وضعه أسفل يمين المشغل مع عناصر التحكم
                params.gravity = Gravity.BOTTOM | Gravity.END;
                params.setMargins(0, 0, 40, 140);
                aiBtn.setLayoutParams(params);

                aiBtn.setOnClickListener(v -> showSettingsDialog(ctx));

                root.addView(aiBtn);
                floatingAiButton = aiBtn;
            });
        } catch (Exception ignored) {}
    }

    /**
     * الواجهة المنبثقة: ترجمة جوجل في 0.1 ثانية + شرح AI الذكي في الخلفية
     */
    private static void showLearningDialog(Context context, String word, String fullSentence) {
        LinearLayout mainLayout = new LinearLayout(context);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 30, 40, 20);
        mainLayout.setBackgroundColor(Color.parseColor("#181825"));

        // شريط العنوان مع زر الإعدادات
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("✨ " + word);
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#89B4FA"));
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        Button settingsBtn = new Button(context);
        settingsBtn.setText("⚙️ إعدادات الـ AI");
        settingsBtn.setTextColor(Color.WHITE);
        settingsBtn.setBackgroundColor(Color.parseColor("#313244"));
        settingsBtn.setTextSize(11f);
        header.addView(settingsBtn);
        mainLayout.addView(header);

        // ترجمة جوجل المباشرة
        TextView instantTv = new TextView(context);
        instantTv.setText("⚡ ترجمة فورية: جاري التحميل...");
        instantTv.setTextSize(15f);
        instantTv.setTextColor(Color.parseColor("#A6E3A1"));
        instantTv.setPadding(0, 15, 0, 10);
        mainLayout.addView(instantTv);

        ProgressBar progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        mainLayout.addView(progressBar);

        // الشرح الذكي للسياق
        ScrollView scrollView = new ScrollView(context);
        TextView aiContentTv = new TextView(context);
        aiContentTv.setText("🤖 جاري استشارة الذكاء الاصطناعي لتحليل السياق والنطق...");
        aiContentTv.setTextColor(Color.parseColor("#CDD6F4"));
        aiContentTv.setTextSize(14f);
        aiContentTv.setLineSpacing(1.2f, 1.2f);
        aiContentTv.setPadding(0, 10, 0, 15);
        scrollView.addView(aiContentTv);
        mainLayout.addView(scrollView);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(mainLayout)
                .setPositiveButton("إغلاق", (d, w) -> d.dismiss())
                .create();
        dialog.show();

        settingsBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showSettingsDialog(context);
        });

        // 1. تشغيل ترجمة جوجل الفورية
        executor.execute(() -> {
            String quick = fetchGoogleTranslate(word);
            mainHandler.post(() -> instantTv.setText("⚡ المعنى المباشر: " + quick));
        });

        // 2. تشغيل الذكاء الاصطناعي (Gemini / Groq)
        executor.execute(() -> {
            String aiResult = fetchAiAnalysis(context, word, fullSentence);
            mainHandler.post(() -> {
                progressBar.setVisibility(ProgressBar.GONE);
                aiContentTv.setText(aiResult);
            });
        });
    }

    private static String fetchGoogleTranslate(String text) {
        try {
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ar&dt=t&q="
                    + URLEncoder.encode(text, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(3000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            return new JSONArray(sb.toString()).getJSONArray(0).getJSONArray(0).getString(0);
        } catch (Exception e) {
            return text;
        }
    }

    private static String fetchAiAnalysis(Context context, String word, String sentence) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String geminiKey = prefs.getString(KEY_GEMINI, "").trim();
        String groqKey = prefs.getString(KEY_GROQ, "").trim();

        if (geminiKey.isEmpty() && groqKey.isEmpty()) {
            return "💡 تنبيه: لم تضع مفتاح الذكاء الاصطناعي بعد!\nاضغط على (⚙️ إعدادات الـ AI) بالأعلى لوضع مفتاح Gemini أو Groq المجاني لتفعيل الشرح الذكي.";
        }

        String prompt = "Explain the English word '" + word + "' in the context of: \"" + sentence + "\".\n"
                + "Answer in Arabic concisely:\n"
                + "📖 المعنى السياقي: ...\n"
                + "🗣️ النطق والنوع: ...\n"
                + "💡 مثال توضيحي: ...";

        if (!geminiKey.isEmpty()) {
            try {
                String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + geminiKey;
                JSONObject json = new JSONObject().put("contents", new JSONArray().put(
                        new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))
                ));
                String res = postHttp(urlStr, json.toString(), null);
                return new JSONObject(res).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            } catch (Exception ignored) {}
        }

        if (!groqKey.isEmpty()) {
            try {
                String urlStr = "https://api.groq.com/openai/v1/chat/completions";
                JSONObject json = new JSONObject().put("model", "llama-3.3-70b-versatile")
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));
                String res = postHttp(urlStr, json.toString(), "Bearer " + groqKey);
                return new JSONObject(res).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");
            } catch (Exception ignored) {}
        }

        return "تعذر الاتصال بالذكاء الاصطناعي، يرجى التأكد من صلاحية المفتاح في الإعدادات.";
    }

    private static String postHttp(String urlStr, String body, String auth) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (auth != null) conn.setRequestProperty("Authorization", auth);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    /**
     * نافذة الإعدادات الشاملة (تفتح من الزر العائم أو من نافذة الترجمة)
     */
    public static void showSettingsDialog(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(45, 30, 45, 20);
        layout.setBackgroundColor(Color.parseColor("#1E1E2E"));

        TextView title = new TextView(context);
        title.setText("⚙️ إعدادات ترجمة الذكاء الاصطناعي");
        title.setTextSize(17f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#89B4FA"));
        layout.addView(title);

        TextView geminiLabel = new TextView(context);
        geminiLabel.setText("\nمفتاح Google Gemini API:");
        geminiLabel.setTextColor(Color.WHITE);
        layout.addView(geminiLabel);

        EditText geminiEt = new EditText(context);
        geminiEt.setHint("AIzaSy...");
        geminiEt.setHintTextColor(Color.GRAY);
        geminiEt.setTextColor(Color.WHITE);
        geminiEt.setText(prefs.getString(KEY_GEMINI, ""));
        layout.addView(geminiEt);

        TextView groqLabel = new TextView(context);
        groqLabel.setText("\nمفتاح Groq API (اختياري / بديل سريع):");
        groqLabel.setTextColor(Color.WHITE);
        layout.addView(groqLabel);

        EditText groqEt = new EditText(context);
        groqEt.setHint("gsk_...");
        groqEt.setHintTextColor(Color.GRAY);
        groqEt.setTextColor(Color.WHITE);
        groqEt.setText(prefs.getString(KEY_GROQ, ""));
        layout.addView(groqEt);

        new AlertDialog.Builder(context)
                .setView(layout)
                .setPositiveButton("💾 حفظ", (d, w) -> {
                    prefs.edit()
                            .putString(KEY_GEMINI, geminiEt.getText().toString().trim())
                            .putString(KEY_GROQ, groqEt.getText().toString().trim())
                            .apply();
                    Toast.makeText(context, "تم حفظ الإعدادات بنجاح! 🚀", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }
}
