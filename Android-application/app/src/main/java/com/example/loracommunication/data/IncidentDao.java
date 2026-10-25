package com.example.loracommunication.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface IncidentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(Incident incident);

    @Query("SELECT * FROM incidents ORDER BY acknowledged ASC, receivedAt DESC")
    LiveData<List<Incident>> getAllIncidents();

    @Query("SELECT * FROM incidents ORDER BY acknowledged ASC, receivedAt DESC")
    List<Incident> getAllIncidentsSync();

    @Query("SELECT COUNT(*) FROM incidents WHERE acknowledged = 0")
    LiveData<Integer> getUnacknowledgedCount();

    @Query("SELECT COUNT(*) FROM incidents WHERE acknowledged = 0")
    int getUnacknowledgedCountSync();

    @Query("SELECT * FROM incidents WHERE id = :id")
    Incident getById(long id);

    @Query("UPDATE incidents SET acknowledged = 1 WHERE id = :id")
    void acknowledge(long id);

    @Query("UPDATE incidents SET acknowledged = 1 WHERE acknowledged = 0")
    void acknowledgeAll();

    @Query("SELECT * FROM incidents WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY receivedAt DESC")
    LiveData<List<Incident>> getIncidentsWithLocation();

    @Query("SELECT * FROM incidents WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY receivedAt DESC")
    List<Incident> getIncidentsWithLocationSync();

    @Query("DELETE FROM incidents")
    void deleteAll();
}
