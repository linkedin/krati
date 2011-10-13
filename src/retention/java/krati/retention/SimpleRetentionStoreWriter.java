package krati.retention;

import java.io.IOException;

import org.apache.log4j.Logger;

import krati.retention.clock.Clock;
import krati.retention.clock.WaterMarksClock;
import krati.store.DataStore;

/**
 * SimpleRetentionStoreWriter
 * 
 * @param <K> Key
 * @param <V> Value
 * @version 0.4.2
 * @author jwu
 * 
 * <p>
 * 08/16, 2011 - Created <br/>
 */
public class SimpleRetentionStoreWriter<K, V> implements RetentionStoreWriter<K, V> {
    private final static Logger _logger = Logger.getLogger(SimpleRetentionStoreWriter.class);
    private final String _source;
    private final DataStore<K, V> _store;
    private final Retention<K> _retention;
    private final WaterMarksClock _waterMarksClock;
    private volatile long _lwmScn = 0;
    private volatile long _hwmScn = 0;
    
    /**
     * Creates a new RetentionStoreWriter instance.
     * 
     * @param source    - the source of store
     * @param retention - the retention for store update events.  
     * @param store     - the store
     * @param waterMarksClock
     */
    public SimpleRetentionStoreWriter(String source, Retention<K> retention, DataStore<K, V> store, WaterMarksClock waterMarksClock) {
        this._source = source;
        this._retention = retention;
        this._store = store;
        this._waterMarksClock = waterMarksClock;
        
        // Initialize the high water mark scn
        _hwmScn = waterMarksClock.getHWMScn(source);
        
        // Initialize the low water mark scn
        _lwmScn = waterMarksClock.getLWMScn(source);
        
        // Initialize the water mark scn from clock if necessary
        if(waterMarksClock.hasSource(source)) {
            Clock clock = retention.getMaxClock();
            long scn = waterMarksClock.getWaterMark(source, clock);
            _hwmScn = Math.min(_hwmScn, scn);
            _lwmScn = Math.min(_lwmScn, _hwmScn);
        }
        
        // Reset water marks
        waterMarksClock.updateWaterMarks(source, _lwmScn, _hwmScn);
        
        // Log water marks
        getLogger().info(String.format("%s since[lwm=%d hwm=%d]", source, _lwmScn, _hwmScn));
    }
    
    protected Logger getLogger() {
        return _logger;
    }
    
    public final DataStore<K, V> getStore() {
        return _store;
    }
    
    public final Retention<K> getRetention() {
        return _retention;
    }
    
    @Override
    public final String getSource() {
        return _source;
    }
    
    @Override
    public long getLWMark() {
        return _lwmScn;
    }
    
    @Override
    public long getHWMark() {
        return _hwmScn;
    }
    
    @Override
    public synchronized void saveHWMark(long hwMark) {
        if(hwMark > _hwmScn) {
            _hwmScn = hwMark;
            _waterMarksClock.setHWMark(_source, _hwmScn);
        }
    }
    
    @Override
    public synchronized void persist() throws IOException {
        _store.persist();
        _waterMarksClock.setHWMark(_source, _hwmScn);
        _waterMarksClock.syncWaterMarks(_source);
    }
    
    @Override
    public synchronized void sync() throws IOException {
        _store.sync();
        _waterMarksClock.setHWMark(_source, _hwmScn);
        _waterMarksClock.syncWaterMarks(_source);
    }
    
    @Override
    public synchronized boolean put(K key, V value, long scn) throws Exception {
        if(scn >= _hwmScn) {
            Clock clock;
            
            _store.put(key, value);
            clock = (scn == _hwmScn) ?
                    _waterMarksClock.current() :
                    _waterMarksClock.updateHWMark(_source, scn);
            _retention.put(new SimpleEvent<K>(key, clock));
            _hwmScn = scn;
            
            return true;
        } else {
            return false;
        }
    }
    
    @Override
    public synchronized boolean delete(K key, long scn) throws Exception {
        if(scn >= _hwmScn) {
            Clock clock;
            
            _store.delete(key);
            clock = (scn == _hwmScn) ?
                    _waterMarksClock.current() :
                    _waterMarksClock.updateHWMark(_source, scn);
            _retention.put(new SimpleEvent<K>(key, clock));
            _hwmScn = scn;
            
            return true;
        } else {
            return false;
        }
    }
}