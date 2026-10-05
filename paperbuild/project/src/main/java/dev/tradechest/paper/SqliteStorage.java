package dev.tradechest.paper;

import org.bukkit.inventory.ItemStack;
import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

public final class SqliteStorage implements AutoCloseable {
    private final File databaseFile; private final Logger logger; private Connection connection;
    public SqliteStorage(File databaseFile,Logger logger){this.databaseFile=databaseFile;this.logger=logger;}
    public synchronized void open() throws SQLException {
        if(connection!=null&&!connection.isClosed())return;
        File parent=databaseFile.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new SQLException("Could not create plugin data directory: "+parent);
        connection=DriverManager.getConnection("jdbc:sqlite:"+databaseFile.getAbsolutePath());
        try(Statement statement=connection.createStatement()){
            statement.execute("PRAGMA journal_mode=WAL");statement.execute("PRAGMA synchronous=FULL");statement.execute("PRAGMA foreign_keys=ON");statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS trade_chests (" +
"world_uuid TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL," +
"chest_id TEXT NOT NULL,owner_uuid TEXT,items BLOB NOT NULL,updated_at INTEGER NOT NULL," +
"PRIMARY KEY (world_uuid,x,y,z))");
        }
    }
    public synchronized List<TradeChestRecord> loadAll() throws SQLException {
        ensureOpen();List<TradeChestRecord> records=new ArrayList<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT world_uuid,x,y,z,chest_id,owner_uuid,items FROM trade_chests");ResultSet rs=ps.executeQuery()){
            while(rs.next())try{
                ChestKey key=new ChestKey(UUID.fromString(rs.getString(1)),rs.getInt(2),rs.getInt(3),rs.getInt(4));
                UUID chestId=UUID.fromString(rs.getString(5));String ownerRaw=rs.getString(6);UUID ownerId=ownerRaw==null?null:UUID.fromString(ownerRaw);
                ItemStack[] decoded=ItemStack.deserializeItemsFromBytes(rs.getBytes(7));records.add(new TradeChestRecord(key,chestId,ownerId,decoded));
            }catch(RuntimeException ex){logger.severe("Skipping unreadable Trade Chest database row: "+ex.getMessage());}
        }
        return records;
    }
    public synchronized void save(TradeChestRecord record)throws SQLException{ensureOpen();saveInternal(record);}
    public synchronized void saveAll(Collection<TradeChestRecord> records)throws SQLException{ensureOpen();boolean old=connection.getAutoCommit();connection.setAutoCommit(false);try{for(TradeChestRecord r:records)saveInternal(r);connection.commit();}catch(SQLException|RuntimeException ex){connection.rollback();throw ex;}finally{connection.setAutoCommit(old);}}
    public synchronized void delete(ChestKey key)throws SQLException{ensureOpen();try(PreparedStatement ps=connection.prepareStatement("DELETE FROM trade_chests WHERE world_uuid=? AND x=? AND y=? AND z=?")){bindKey(ps,key);ps.executeUpdate();}}
    private void saveInternal(TradeChestRecord record)throws SQLException{
        byte[] bytes=ItemStack.serializeItemsAsBytes(record.snapshot());
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO trade_chests(world_uuid,x,y,z,chest_id,owner_uuid,items,updated_at) " +
"VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(world_uuid,x,y,z) DO UPDATE SET " +
"chest_id=excluded.chest_id,owner_uuid=excluded.owner_uuid,items=excluded.items,updated_at=excluded.updated_at")){
            bindKey(ps,record.key());ps.setString(5,record.chestId().toString());if(record.ownerId()==null)ps.setNull(6,Types.VARCHAR);else ps.setString(6,record.ownerId().toString());ps.setBytes(7,bytes);ps.setLong(8,Instant.now().toEpochMilli());ps.executeUpdate();
        }
    }
    private static void bindKey(PreparedStatement ps,ChestKey key)throws SQLException{ps.setString(1,key.worldId().toString());ps.setInt(2,key.x());ps.setInt(3,key.y());ps.setInt(4,key.z());}
    private void ensureOpen()throws SQLException{if(connection==null||connection.isClosed())throw new SQLException("Trade Chest database is not open");}
    @Override public synchronized void close()throws SQLException{if(connection!=null){connection.close();connection=null;}}
}
