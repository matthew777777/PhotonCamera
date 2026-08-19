package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;

import java.util.List;

/**
 * Dialog for creating, editing and deleting a {@link VendorTagUtils.TunableKey}.
 * Pass editIndex = -1 to create a new key.
 */
public class TunableKeyDialog {
    private static final String[] VALUE_TYPES = {"Integer", "Long", "Float", "Double", "Byte", "Short", "int[]", "byte[]", "long[]", "float[]", "String"};

    private TunableKeyDialog() {}

    public static void show(Context context, String sensorId, int editIndex, Runnable onDone) {
        if (context == null || sensorId == null) return;
        List<VendorTagUtils.TunableKey> keys = TunableKeyManager.loadKeys(context, sensorId);
        VendorTagUtils.TunableKey existing = (editIndex >= 0 && editIndex < keys.size()) ? keys.get(editIndex) : null;
        boolean isNew = existing == null;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText nameEdit = addEdit(context, container, "Key name", existing != null ? existing.name : "");
        Spinner valueTypeSpinner = addSpinner(context, container, "Value type", VALUE_TYPES, existing != null ? existing.valueType : "Integer");
        EditText valueEdit = addEdit(context, container, "Value", existing != null ? existing.value : "0");

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(isNew ? "Add Tunable Key" : "Edit Tunable Key");
        builder.setView(container);
        builder.setPositiveButton("Set", (dialog, which) -> {
            String name = nameEdit.getText().toString().trim();
            if (name.isEmpty()) {
                PhotonCamera.showToast("Key name cannot be empty");
                return;
            }
            VendorTagUtils.TunableKey key = new VendorTagUtils.TunableKey();
            key.type = "CaptureRequest";
            key.name = name;
            key.valueType = (String) valueTypeSpinner.getSelectedItem();
            key.value = valueEdit.getText().toString().trim();
            key.tested = existing != null && existing.tested;
            key.supported = existing != null && existing.supported;

            List<VendorTagUtils.TunableKey> list = TunableKeyManager.loadKeys(context, sensorId);
            if (isNew) {
                list.add(key);
            } else if (editIndex < list.size()) {
                list.set(editIndex, key);
            }
            TunableKeyManager.saveKeys(context, sensorId, list);
            if (onDone != null) onDone.run();
        });
        if (!isNew) {
            builder.setNeutralButton("Delete", (dialog, which) -> {
                List<VendorTagUtils.TunableKey> list = TunableKeyManager.loadKeys(context, sensorId);
                if (editIndex >= 0 && editIndex < list.size()) {
                    list.remove(editIndex);
                    TunableKeyManager.saveKeys(context, sensorId, list);
                }
                if (onDone != null) onDone.run();
            });
        }
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.show();
        nameEdit.requestFocus();
    }

    private static TextView label(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(0, dp(context, 8), 0, 0);
        return tv;
    }

    private static EditText addEdit(Context context, LinearLayout container, String label, String value) {
        container.addView(label(context, label));
        EditText edit = new EditText(context);
        edit.setSingleLine(true);
        edit.setText(value);
        container.addView(edit);
        return edit;
    }

    private static Spinner addSpinner(Context context, LinearLayout container, String label, String[] items, String selection) {
        container.addView(label(context, label));
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int idx = -1;
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(selection)) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) spinner.setSelection(idx);
        container.addView(spinner);
        return spinner;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
