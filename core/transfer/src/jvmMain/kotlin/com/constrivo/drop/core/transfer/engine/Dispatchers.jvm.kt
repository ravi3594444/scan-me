package com.constrivo.drop.core.transfer.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun defaultIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
