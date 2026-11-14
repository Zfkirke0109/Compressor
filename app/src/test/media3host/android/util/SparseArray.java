package android.util;

import java.util.ArrayList;
import java.util.TreeMap;

/** Host replacement for the SDK stub, which throws: an int-keyed map in ascending key order. */
public class SparseArray<E> {
    private final TreeMap<Integer, E> map = new TreeMap<>();

    public SparseArray() { }

    public SparseArray(int initialCapacity) { }

    public E get(int key) { return map.get(key); }

    public E get(int key, E valueIfKeyNotFound) { return map.containsKey(key) ? map.get(key) : valueIfKeyNotFound; }

    public void put(int key, E value) { map.put(key, value); }

    public void append(int key, E value) { map.put(key, value); }

    public void remove(int key) { map.remove(key); }

    public void delete(int key) { map.remove(key); }

    public int size() { return map.size(); }

    public void clear() { map.clear(); }

    public boolean contains(int key) { return map.containsKey(key); }

    public int indexOfKey(int key) { return map.containsKey(key) ? map.headMap(key).size() : -1; }

    public int keyAt(int index) { return new ArrayList<>(map.keySet()).get(index); }

    public E valueAt(int index) { return new ArrayList<>(map.values()).get(index); }

    public void removeAt(int index) { map.remove(keyAt(index)); }
}
