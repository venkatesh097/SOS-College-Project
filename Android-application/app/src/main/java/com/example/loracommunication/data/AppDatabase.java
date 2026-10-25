package com.example.loracommunication.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Message.class, Incident.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract MessageDao messageDao();
    public abstract IncidentDao incidentDao();

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `messages_new` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`node` TEXT, "
                    + "`packetId` INTEGER, "
                    + "`direction` TEXT, "
                    + "`kind` TEXT, "
                    + "`content` TEXT, "
                    + "`timestamp` INTEGER NOT NULL, "
                    + "`latitude` REAL, "
                    + "`longitude` REAL, "
                    + "`sendState` TEXT)");

            database.execSQL("INSERT INTO `messages_new` (`id`, `node`, `packetId`, `direction`, `kind`, `content`, `timestamp`, `latitude`, `longitude`, `sendState`) "
                    + "SELECT `id`, "
                    + "`senderId` AS `node`, "
                    + "NULL AS `packetId`, "
                    + "CASE WHEN `isSent` = 1 THEN 'SENT' ELSE 'RECEIVED' END AS `direction`, "
                    + "CASE WHEN `isSos` = 1 THEN 'SOS' ELSE 'MSG' END AS `kind`, "
                    + "`content`, "
                    + "`timestamp`, "
                    + "CASE WHEN `latitude` = 0.0 AND `longitude` = 0.0 THEN NULL ELSE `latitude` END AS `latitude`, "
                    + "CASE WHEN `latitude` = 0.0 AND `longitude` = 0.0 THEN NULL ELSE `longitude` END AS `longitude`, "
                    + "CASE WHEN `isSent` = 1 THEN 'SENT_OVER_LORA' ELSE 'SENT_OVER_LORA' END AS `sendState` "
                    + "FROM `messages`");

            database.execSQL("DROP TABLE `messages`");
            database.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`");

            database.execSQL("CREATE TABLE IF NOT EXISTS `incidents` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`kind` TEXT, "
                    + "`node` TEXT, "
                    + "`packetId` INTEGER, "
                    + "`lat` REAL, "
                    + "`lon` REAL, "
                    + "`receivedAt` INTEGER NOT NULL, "
                    + "`acknowledged` INTEGER NOT NULL)");

            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_incidents_node_packetId_kind` "
                    + "ON `incidents` (`node`, `packetId`, `kind`)");
        }
    };

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "lora_database")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
