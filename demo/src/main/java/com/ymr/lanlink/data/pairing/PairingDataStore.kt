package com.ymr.lanlink.data.pairing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Process-wide singleton DataStore for lanlink pairing state (server records +
 * client credential). DataStore requires exactly one active instance per file
 * for the whole process; the `preferencesDataStore` property delegate enforces
 * that, so always read through [Context.pairingDataStore].
 */
val Context.pairingDataStore: DataStore<Preferences> by preferencesDataStore(name = "lanlink_pairing")
