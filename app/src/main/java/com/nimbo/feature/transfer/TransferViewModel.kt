package com.nimbo.feature.transfer

import androidx.lifecycle.ViewModel
import com.nimbo.data.transfer.BatchUi
import com.nimbo.data.transfer.PendingBatch
import com.nimbo.data.transfer.ResolveMode
import com.nimbo.data.transfer.TransferManager
import com.nimbo.data.transfer.TransferNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class TransferViewModel @Inject constructor(private val transfers: TransferManager) : ViewModel() {
    val batches: StateFlow<List<BatchUi>> = transfers.batches
    val failed: StateFlow<List<String>> = transfers.failed
    val notice: StateFlow<TransferNotice?> = transfers.notice
    val pending: StateFlow<PendingBatch?> = transfers.pending

    fun cancel(id: Long) = transfers.cancelBatch(id)
    fun resolve(mode: ResolveMode?) = transfers.resolvePending(mode)
    fun clearFailed() = transfers.clearFailed()
    fun clearNotice() = transfers.clearNotice()
}
