import androidx.datastore.preferences.core.*;
import java.io.*;
import kotlin.Unit;
import kotlin.coroutines.*;

/** Synthetic bytes produced by the original 1.1.7 serializer, outside the upgraded app classpath. */
public final class LegacyPreferencesFixture {
    public static void main(String[] args) throws Exception {
        MutablePreferences p = PreferencesFactory.createMutable();
        p.set(PreferencesKeys.booleanKey("onboarded"), true);
        p.set(PreferencesKeys.stringKey("appearance"), "dark");
        p.set(PreferencesKeys.booleanKey("metric"), false);
        p.set(PreferencesKeys.intKey("distance_filter"), 50);
        p.set(PreferencesKeys.intKey("stop_after_hours"), 4);
        p.set(PreferencesKeys.booleanKey("network_geocoding"), false);
        p.set(PreferencesKeys.intKey("inferred_gap_minutes"), 60);
        p.set(PreferencesKeys.intKey("inferred_minimum_minutes"), 15);
        p.set(PreferencesKeys.intKey("inferred_minimum_points"), 3);
        p.set(PreferencesKeys.booleanKey("show_outliers"), true);
        p.set(PreferencesKeys.stringKey("photo_selection_epoch"), "synthetic-legacy-epoch");
        p.set(PreferencesKeys.booleanKey("road_display_enabled"), false);
        p.set(PreferencesKeys.stringKey("road_access_revision"), "synthetic-legacy-revision");
        Continuation<Unit> continuation = new Continuation<>() {
            public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            public void resumeWith(Object result) { throw new AssertionError("Unexpected asynchronous serializer"); }
        };
        try (OutputStream out = new FileOutputStream(args[0])) {
            Object result = PreferencesFileSerializer.INSTANCE.writeTo(p, out, continuation);
            if (result != Unit.INSTANCE) throw new AssertionError("Serializer did not complete synchronously");
        }
    }
}
