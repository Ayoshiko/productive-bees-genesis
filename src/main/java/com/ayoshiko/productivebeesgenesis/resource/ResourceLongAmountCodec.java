package com.ayoshiko.productivebeesgenesis.resource;
import com.mojang.serialization.*;
/** Independent holder prevents recipe registration order from creating a codec cycle. */
public final class ResourceLongAmountCodec {
 public static final Codec<Long> CODEC=Codec.STRING.comapFlatMap(value->{
  if(!value.matches("[1-9][0-9]{0,18}"))return DataResult.error(()->"amount must be a positive decimal string");
  try{return DataResult.success(Long.parseLong(value));}catch(NumberFormatException e){return DataResult.error(()->"amount exceeds Long.MAX_VALUE");}
 },Object::toString);
}
