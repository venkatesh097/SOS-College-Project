package com.example.loracommunication.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface MessageDao {
    @Insert
    long insert(Message message);

    @Update
    void update(Message message);

    @Delete
    void delete(Message message);

    // Chat messages (oldest first)
    @Query("SELECT * FROM messages WHERE kind = 'MSG' ORDER BY timestamp ASC")
    LiveData<List<Message>> getChatMessages();

    @Query("SELECT * FROM messages WHERE kind = 'MSG' ORDER BY timestamp ASC")
    List<Message> getChatMessagesSync();

    // All messages ascending (oldest first)
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    LiveData<List<Message>> getAllMessagesAscending();

    // All messages descending (newest first) for history
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    LiveData<List<Message>> getAllMessages();

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    List<Message> getAllMessagesSync();

    // Filter by kind (e.g. MSG, SOS, FALL)
    @Query("SELECT * FROM messages WHERE kind = :kind ORDER BY timestamp DESC")
    LiveData<List<Message>> getMessagesByKind(String kind);

    @Query("SELECT * FROM messages WHERE kind = :kind ORDER BY timestamp DESC")
    List<Message> getMessagesByKindSync(String kind);

    // Get latest SENDING message of a specific kind
    @Query("SELECT * FROM messages WHERE kind = :kind AND sendState = 'SENDING' ORDER BY timestamp DESC LIMIT 1")
    Message getLatestSendingMessage(String kind);

    // Get latest SENDING message of any kind
    @Query("SELECT * FROM messages WHERE sendState = 'SENDING' ORDER BY timestamp DESC LIMIT 1")
    Message getLatestSendingMessageAny();

    @Query("UPDATE messages SET sendState = :newState WHERE id = :id")
    void updateSendState(long id, String newState);

    @Query("UPDATE messages SET sendState = :newState, packetId = :packetId WHERE id = :id")
    void updateSendStateAndPacketId(long id, String newState, Long packetId);

    @Query("UPDATE messages SET sendState = :newState, packetId = :packetId, latitude = :lat, longitude = :lon WHERE id = :id")
    void updateSendStateAndDetails(long id, String newState, Long packetId, Double lat, Double lon);

    @Query("UPDATE messages SET sendState = 'FAILED' WHERE id = (SELECT id FROM messages WHERE sendState = 'SENDING' ORDER BY timestamp DESC LIMIT 1)")
    int markLatestSendingFailed();

    @Query("DELETE FROM messages")
    void deleteAll();
}
