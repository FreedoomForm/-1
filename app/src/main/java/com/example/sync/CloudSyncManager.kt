package com.example.sync

import android.util.Log
import com.example.data.Renter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class CloudSyncManager {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    suspend fun syncRentersToCloud(renters: List<Renter>) {
        val user = auth.currentUser ?: return
        
        try {
            val userDocRef = db.collection("users").document(user.uid)
            userDocRef.set(mapOf("lastSync" to System.currentTimeMillis()), SetOptions.merge()).await()
            
            val rentersCollection = userDocRef.collection("renters")
            
            // For a simple backup, we overwrite the cloud state with the local state.
            for (renter in renters) {
                rentersCollection.document(renter.id.toString()).set(renter).await()
            }
            Log.d("CloudSyncManager", "Successfully synced \${renters.size} renters to cloud.")
        } catch (e: Exception) {
            Log.e("CloudSyncManager", "Failed to sync to cloud", e)
        }
    }
}
