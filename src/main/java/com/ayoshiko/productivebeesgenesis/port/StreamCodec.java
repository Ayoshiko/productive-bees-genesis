package com.ayoshiko.productivebeesgenesis.port;
public interface StreamCodec<B,T> {
 T decode(B buffer); void encode(B buffer,T value);
 default <U> StreamCodec<B,U> map(java.util.function.Function<T,U> from,java.util.function.Function<U,T> to) {
  var codec=this;return new StreamCodec<>(){public U decode(B buffer){return from.apply(codec.decode(buffer));} public void encode(B buffer,U value){codec.encode(buffer,to.apply(value));}};
 }
}
