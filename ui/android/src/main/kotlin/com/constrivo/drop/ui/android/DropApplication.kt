package com.constrivo.drop.ui.android

import android.app.Application

/**
 * The application: holds the process's one [AppGraph] (the shared UI's controller and its Android ports), created
 * when the first activity asks for it.
 */
class DropApplication : Application() {
    internal val graph: AppGraph by lazy { AppGraph(this) }
}
