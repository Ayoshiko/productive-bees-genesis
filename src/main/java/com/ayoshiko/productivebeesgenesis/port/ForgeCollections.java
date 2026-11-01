package com.ayoshiko.productivebeesgenesis.port;
import java.util.*;
public final class ForgeCollections {
    public static <K,V> Map.Entry<K,V> pollFirst(Map<K,V> map){var iterator=map.entrySet().iterator();if(!iterator.hasNext())return null;var entry=iterator.next();var copy=new AbstractMap.SimpleImmutableEntry<>(entry);iterator.remove();return copy;}
    public static <T> List<T> reversed(List<T> list){var copy=new ArrayList<>(list);Collections.reverse(copy);return copy;}
    public static <T> T last(List<T> list){return list.get(list.size()-1);}
}
