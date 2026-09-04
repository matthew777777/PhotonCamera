package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

import com.particlesdevs.photoncamera.util.FileManager;
import com.particlesdevs.photoncamera.util.SimpleStorageHelper;

import org.apache.commons.io.FileUtils;

import java.util.Arrays;

public class RestorePreference extends ListPreference {
    public RestorePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setOnPreferenceClickListener(preference -> {
            String[] fileNames = SimpleStorageHelper.listBackupFileNames(context);
            if (fileNames == null || fileNames.length == 0) {
                fileNames = FileManager.sPHOTON_DIR.list((dir, name) -> {
                    String ext = FileUtils.getExtension(name);
                    return ext != null && (ext.equalsIgnoreCase("xml") || ext.equalsIgnoreCase("json"));
                });
            }
            fileNames = fileNames != null ? fileNames : new String[0];
            Arrays.sort(fileNames);
            setEntries(fileNames);
            setEntryValues(fileNames);
            return true;
        });
    }
}
