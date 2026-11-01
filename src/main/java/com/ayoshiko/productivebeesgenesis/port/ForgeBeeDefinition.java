package com.ayoshiko.productivebeesgenesis.port;
import com.google.gson.JsonObject;
import java.util.*;
/** PB 13's named attributes use the same ordering as PB 12's integer genes. */
public final class ForgeBeeDefinition {
 private static final Map<String,List<String>> VALUES=Map.of(
  "weather_tolerance",List.of("none","rain","any"),"temper",List.of("passive","normal","hostile","aggressive"),
  "behavior",List.of("diurnal","nocturnal","metaturnal"),"endurance",List.of("weak","normal","medium","strong"),
  "productivity",List.of("normal","medium","high","very_high"));
 public static JsonObject portableDefinition(JsonObject source){JsonObject result=source.deepCopy();var attributes=result.getAsJsonObject("attributes");if(attributes!=null)VALUES.forEach((key,values)->{var value=attributes.get(key);if(value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()){int index=value.getAsInt();if(index<0||index>=values.size())throw new IllegalArgumentException("Invalid bee gene "+key+": "+index);attributes.addProperty(key,key+"."+values.get(index));}});return result;}
 public static JsonObject nativeDefinition(JsonObject source){
  JsonObject result=source.deepCopy();var attributes=result.getAsJsonObject("attributes");if(attributes==null)return result;
  VALUES.forEach((key,values)->{var value=attributes.get(key);if(value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()){
   String text=value.getAsString();if(text.startsWith(key+"."))text=text.substring(key.length()+1);int index=values.indexOf(text);
   if(index<0)throw new IllegalArgumentException("Unknown bee attribute "+key+": "+text);attributes.addProperty(key,index);
  }});return result;
 }
}
