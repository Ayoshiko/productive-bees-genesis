package com.ayoshiko.productivebeesgenesis.port;
import java.lang.reflect.*;
/** Late-bound access to the inspected JDTE Forge 0.5.8 lifecycle. */
public final class ForgeJdteAccess {
    public static Object field(Object owner,String name){try{Class<?> type=owner instanceof Class<?> c?c:owner.getClass();for(Class<?> c=type;c!=null;c=c.getSuperclass())try{var field=c.getDeclaredField(name);field.setAccessible(true);return field.get(owner instanceof Class<?>?null:owner);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(name);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    public static Object call(Object owner,String name,Object...args){try{
        Class<?> type=owner instanceof Class<?> c?c:owner.getClass();
        for(Class<?> c=type;c!=null;c=c.getSuperclass())for(var method:c.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){
            boolean matches=true;Class<?>[] parameters=method.getParameterTypes();for(int i=0;i<args.length;i++)if(args[i]!=null&&!parameters[i].isPrimitive()&&!parameters[i].isInstance(args[i]))matches=false;
            if(!matches)continue;method.setAccessible(true);return method.invoke(owner instanceof Class<?>?null:owner,args);
        }
        throw new NoSuchMethodException(name);
    }catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof RuntimeException runtime)throw runtime;if(cause instanceof Error error)throw error;throw new IllegalStateException(cause);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    public static Class<?> type(String name){try{return Class.forName(name);}catch(ClassNotFoundException e){throw new IllegalStateException(e);}}
    public static int number(Object owner,String method,Object...args){return ((Number)call(owner,method,args)).intValue();}
    public static double config(String field){return ((Number)call(field(field(type("com.jdte.setup.JDTEConfig"),"COMMON"),field),"get")).doubleValue();}
}
