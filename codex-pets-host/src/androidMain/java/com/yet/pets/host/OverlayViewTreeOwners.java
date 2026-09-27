package com.yet.pets.host;

import android.view.View;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.savedstate.SavedStateRegistryOwner;
import androidx.savedstate.ViewTreeSavedStateRegistryOwner;

/** Android-only bridge to the supported Java-facing ViewTree owner APIs. */
final class OverlayViewTreeOwners {
    private OverlayViewTreeOwners() {}

    static void attach(View view, OverlayLifecycleOwner owner) {
        ViewTreeLifecycleOwner.set(view, (LifecycleOwner) owner);
        ViewTreeSavedStateRegistryOwner.set(view, (SavedStateRegistryOwner) owner);
        ViewTreeViewModelStoreOwner.set(view, (ViewModelStoreOwner) owner);
    }
}
