package maigosoft.mcpdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.preference.PreferenceManager;

import com.readystatesoftware.sqliteasset.SQLiteAssetHelper;

public class MCPDatabase extends SQLiteAssetHelper {

    private Context context;

    private static final String DATABASE_NAME = "mcpdict";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_NAME = "mcpdict";
    public static final String COLUMN_NAME_UNICODE = "unicode";
    public static final String COLUMN_NAME_MC = "mc";
    public static final String COLUMN_NAME_PU = "pu";
    public static final String COLUMN_NAME_CT = "ct";
    public static final String COLUMN_NAME_KR = "kr";
    public static final String COLUMN_NAME_VN = "vn";
    public static final String COLUMN_NAME_JP_GO = "jp_go";
    public static final String COLUMN_NAME_JP_KAN = "jp_kan";
    public static final String COLUMN_NAME_JP_TOU = "jp_tou";
    public static final String COLUMN_NAME_JP_KWAN = "jp_kwan";
    public static final String COLUMN_NAME_JP_OTHER = "jp_other";

    // Must be the same order as defined in the string array "search_as"
    public static final int SEARCH_AS_HZ = 0;
    public static final int SEARCH_AS_MC = 1;
    public static final int SEARCH_AS_PU = 2;
    public static final int SEARCH_AS_CT = 3;
    public static final int SEARCH_AS_KR = 4;
    public static final int SEARCH_AS_VN = 5;
    public static final int SEARCH_AS_JP_GO = 6;
    public static final int SEARCH_AS_JP_KAN = 7;
    public static final int SEARCH_AS_JP_ANY = 8;

    private static final String[] SEARCH_AS_TO_COLUMN_NAME = {
        COLUMN_NAME_UNICODE, COLUMN_NAME_MC,
        COLUMN_NAME_PU, COLUMN_NAME_CT,
        COLUMN_NAME_KR, COLUMN_NAME_VN,
        COLUMN_NAME_JP_GO, COLUMN_NAME_JP_KAN, null
    };

    public MCPDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context;
        setForcedUpgradeVersion(DATABASE_VERSION);
    }

    public Cursor search(String input, int mode) {
        // Get options and settings from SharedPreferences
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        Resources r = context.getResources();
        boolean kuangxYonhOnly = sp.getBoolean(r.getString(R.string.pref_key_kuangx_yonh_only), false);
        boolean allowVariants = sp.getBoolean(r.getString(R.string.pref_key_allow_variants), true);
        boolean toneInsensitive = sp.getBoolean(r.getString(R.string.pref_key_tone_insensitive), false);
        int cantoneseSystem = sp.getInt(r.getString(R.string.pref_key_cantonese_romanization), 0);

        // Split the input string into keywords and canonicalize them
        List<String> keywords = new ArrayList<String>();
        if (mode == SEARCH_AS_HZ) {     // Each character is a query
            for (int i = 0; i < input.length(); i++) {
                char unicode = input.charAt(i);
                if (Orthography.Hanzi.isHanzi(unicode)) {
                    // Only search for Chinese characters in this range
                    if (allowVariants) {
                        for (char c : Orthography.Hanzi.getVariants(unicode)) {
                            keywords.add(String.format("%4X", (int)c));
                        }
                    }
                    else {
                        keywords.add(String.format("%4X", (int)unicode));
                    }
                }
            }
        }
        else {                          // Each contiguous run of "\w" characters or apostrophe is a query
            if (mode == SEARCH_AS_KR) { // For Korean, put separators around all hanguls
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < input.length(); i++) {
                    char c = input.charAt(i);
                    if (Orthography.Korean.isHangul(c)) {
                        sb.append(" " + c + " ");
                    }
                    else {
                        sb.append(c);
                    }
                }
                input = sb.toString();
            }
            for (String token : input.split("[^\\w']+")) {
                if (token.equals("")) continue;
                token = token.toLowerCase(Locale.US);
                // Canonicalization
                switch (mode) {
                    case SEARCH_AS_MC: token = Orthography.MiddleChinese.canonicalize(token); break;
                    case SEARCH_AS_PU: token = Orthography.Mandarin.canonicalize(token); break;
                    case SEARCH_AS_CT: token = Orthography.Cantonese.canonicalize(token, cantoneseSystem); break;
                    case SEARCH_AS_KR: token = Orthography.Korean.canonicalize(token); break;
                    case SEARCH_AS_VN: token = Orthography.Vietnamese.canonicalize(token); break;
                    case SEARCH_AS_JP_GO: case SEARCH_AS_JP_KAN: case SEARCH_AS_JP_ANY:
                                       token = Orthography.Japanese.canonicalize(token); break;
                }
                if (token == null) continue;
                List<String> allTones = null;
                if (toneInsensitive) {
                    switch (mode) {
                        case SEARCH_AS_MC: allTones = Orthography.MiddleChinese.getAllTones(token); break;
                        case SEARCH_AS_PU: allTones = Orthography.Mandarin.getAllTones(token); break;
                        case SEARCH_AS_CT: allTones = Orthography.Cantonese.getAllTones(token); break;
                        case SEARCH_AS_VN: allTones = Orthography.Vietnamese.getAllTones(token); break;
                    }
                }
                if (allTones != null) {
                    keywords.addAll(allTones);
                }
                else {
                    keywords.add(token);
                }
            }
        }
        if (keywords.isEmpty()) return null;

        // Columns to search
        String[] columns = (mode != SEARCH_AS_JP_ANY) ?
                            new String[] {SEARCH_AS_TO_COLUMN_NAME[mode]} :
                            new String[] {COLUMN_NAME_JP_GO, COLUMN_NAME_JP_KAN,
                                          COLUMN_NAME_JP_TOU, COLUMN_NAME_JP_KWAN,
                                          COLUMN_NAME_JP_OTHER};

        // Build inner query statement
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_NAME);
        List<String> queries = new ArrayList<String>();
        List<String> args = new ArrayList<String>();
        for (int i = 0; i < keywords.size(); i++) {
            for (String column : columns) {
                String[] projection = {"rowid AS _id", i + " AS rank"};
                queries.add(qb.buildQuery(projection, column + " MATCH ?", null, null, null, null));
                args.add(keywords.get(i));
            }
        }
        String query = qb.buildUnionQuery(queries.toArray(new String[0]), null, null);

        // Build outer query statement
        qb.setTables("(" + query + ") AS u, " + TABLE_NAME + " AS v");
        qb.setDistinct(true);
        String[] projection = {"_id",
                   COLUMN_NAME_UNICODE, COLUMN_NAME_MC,
                   COLUMN_NAME_PU, COLUMN_NAME_CT,
                   COLUMN_NAME_KR, COLUMN_NAME_VN,
                   COLUMN_NAME_JP_GO, COLUMN_NAME_JP_KAN,
                   COLUMN_NAME_JP_TOU, COLUMN_NAME_JP_KWAN,
                   COLUMN_NAME_JP_OTHER};
        String selection = "u._id = v.rowid";
        if (kuangxYonhOnly) {
            selection += " AND " + COLUMN_NAME_MC + " IS NOT NULL";
        }
        query = qb.buildQuery(projection, selection, null, null, "rank", null);

        // Search
        SQLiteDatabase db = getReadableDatabase();
        Cursor data = db.rawQuery(query, args.toArray(new String[0]));
        return data;
    }
}