package com.example.utechat.core.common;

import android.content.Context;

import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

/** Localized resources for services and repositories that hold an application context. */
public final class AppLanguage {
    private AppLanguage() { }

    public static String tag(Context context) {
        android.os.LocaleList locales = ContextCompat.getContextForLanguage(context).getResources().getConfiguration().getLocales();
        for (int index = 0; index < locales.size(); index++) {
            String language = locales.get(index).getLanguage();
            if ("en".equals(language) || "vi".equals(language)) return language;
        }
        return "vi";
    }

    public static String text(Context context, @StringRes int resource, Object... args) {
        Context localized = ContextCompat.getContextForLanguage(context);
        return args.length == 0 ? localized.getString(resource) : localized.getString(resource, args);
    }
}
