package com.xabber.android.ui.preferences;

import android.os.Bundle;
import android.support.v7.widget.Toolbar;

import com.xabber.android.R;
import com.xabber.android.ui.activity.ManagedActivity;
import com.xabber.android.ui.activity.PreferenceSummaryHelperActivity;
import com.xabber.android.ui.color.BarPainter;
import com.xabber.android.ui.helper.ToolbarHelper;

public class SecuritySettings extends ManagedActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isFinishing())
            return;

        setContentView(R.layout.activity_with_toolbar_and_container);

        String title = PreferenceSummaryHelperActivity.getPreferenceTitle(getString(R.string.preference_security));
        Toolbar toolbar = ToolbarHelper.setUpDefaultToolbar(this, title);

        BarPainter barPainter = new BarPainter(this, toolbar);
        barPainter.setDefaultColor();

        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, new SecuritySettingsFragment()).commit();
        }
    }
}
