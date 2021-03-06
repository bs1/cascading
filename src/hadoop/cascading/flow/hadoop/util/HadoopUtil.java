/*
 * Copyright (c) 2007-2012 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cascading.flow.hadoop.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import cascading.flow.FlowException;
import cascading.flow.hadoop.HadoopFlowProcess;
import cascading.scheme.hadoop.TextLine;
import cascading.tap.SinkMode;
import cascading.tap.hadoop.Hfs;
import cascading.tap.hadoop.Lfs;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntryCollector;
import cascading.tuple.TupleEntryIterator;
import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class HadoopUtil
  {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger( HadoopUtil.class );

  public static void initLog4j( JobConf jobConf )
    {
    String values = jobConf.get( "log4j.logger", null );

    if( values == null || values.length() == 0 )
      return;

    String[] elements = values.split( "," );

    for( String element : elements )
      {
      String[] logger = element.split( "=" );

      Logger.getLogger( logger[ 0 ] ).setLevel( Level.toLevel( logger[ 1 ] ) );
      }
    }

  public static JobConf createJobConf( Map<Object, Object> properties, JobConf defaultJobconf )
    {
    JobConf jobConf = defaultJobconf == null ? new JobConf() : new JobConf( defaultJobconf );

    if( properties == null )
      return jobConf;

    Set<Object> keys = new HashSet<Object>( properties.keySet() );

    // keys will only be grabbed if both key/value are String, so keep orig keys
    if( properties instanceof Properties )
      keys.addAll( ( (Properties) properties ).stringPropertyNames() );

    for( Object key : keys )
      {
      Object value = properties.get( key );

      if( value == null && properties instanceof Properties && key instanceof String )
        value = ( (Properties) properties ).getProperty( (String) key );

      if( value == null ) // don't stuff null values
        continue;

      // don't let these objects pass, even though toString is called below.
      if( value instanceof Class || value instanceof JobConf )
        continue;

      jobConf.set( key.toString(), value.toString() );
      }

    return jobConf;
    }

  public static Map<Object, Object> createProperties( Configuration jobConf )
    {
    Map<Object, Object> properties = new HashMap<Object, Object>();

    for( Map.Entry<String, String> entry : jobConf )
      properties.put( entry.getKey(), entry.getValue() );

    return properties;
    }

  public static Thread getHDFSShutdownHook()
    {
    Exception caughtException = null;

    try
      {
      // we must init the FS so the finalizer is registered
      FileSystem.getLocal( new JobConf() );

      Field field = FileSystem.class.getDeclaredField( "clientFinalizer" );
      field.setAccessible( true );

      Thread finalizer = (Thread) field.get( null );

      if( finalizer != null )
        Runtime.getRuntime().removeShutdownHook( finalizer );

      return finalizer;
      }
    catch( NoSuchFieldException exception )
      {
      caughtException = exception;
      }
    catch( IllegalAccessException exception )
      {
      caughtException = exception;
      }
    catch( IOException exception )
      {
      caughtException = exception;
      }

    LOG.debug( "unable to find and remove client hdfs shutdown hook, received exception: {}", caughtException.getClass().getName() );

    return null;
    }

  public static String serializeMapBase64( Map<String, String> map, boolean compress ) throws IOException
    {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ObjectOutputStream out = new ObjectOutputStream( compress ? new GZIPOutputStream( bytes ) : bytes );

    try
      {
      out.writeInt( map.size() );

      for( Map.Entry<String, String> entry : map.entrySet() )
        writeKeyValue( out, entry.getKey(), entry.getValue() );
      }
    finally
      {
      out.close();
      }

    return new String( Base64.encodeBase64( bytes.toByteArray() ) );
    }

  public static Map<String, String> deserializeMapBase64( String string, boolean decompress ) throws IOException
    {
    if( string == null || string.length() == 0 )
      return null;

    ObjectInputStream in = null;

    try
      {
      ByteArrayInputStream bytes = new ByteArrayInputStream( Base64.decodeBase64( string.getBytes() ) );

      in = new ObjectInputStream( decompress ? new GZIPInputStream( bytes ) : bytes );

      int mapSize = in.readInt();
      Map<String, String> map = new HashMap<String, String>( mapSize );

      for( int j = 0; j < mapSize; j++ )
        map.put( in.readUTF(), readStringAsObject( in ) );

      return map;
      }
    finally
      {
      if( in != null )
        in.close();
      }
    }

  public static String serializeListMapBase64( List<Map<String, String>> listMap, boolean compress ) throws IOException
    {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    ObjectOutputStream out = new ObjectOutputStream( compress ? new GZIPOutputStream( bytes ) : bytes );

    try
      {
      out.writeInt( listMap.size() );

      for( Map<String, String> map : listMap )
        {
        out.writeInt( map.size() );

        for( Map.Entry<String, String> entry : map.entrySet() )
          writeKeyValue( out, entry.getKey(), entry.getValue() );
        }
      }
    finally
      {
      out.close();
      }

    return new String( Base64.encodeBase64( bytes.toByteArray() ) );
    }

  private static void writeKeyValue( ObjectOutputStream out, String key, String value ) throws IOException
    {
    try
      {
      out.writeUTF( key );
      // we must writeObject as it knows how to handle large Strings larger than Short max value
      out.writeObject( value );
      }
    catch( IOException exception )
      {
      LOG.error( "could not write key/value: " + key + "/" + value, exception );
      throw exception;
      }
    }

  public static List<Map<String, String>> deserializeListMapBase64( String string, boolean decompress ) throws IOException
    {
    if( string == null || string.length() == 0 )
      return null;

    ObjectInputStream in = null;

    try
      {
      ByteArrayInputStream bytes = new ByteArrayInputStream( Base64.decodeBase64( string.getBytes() ) );

      in = new ObjectInputStream( decompress ? new GZIPInputStream( bytes ) : bytes );

      int listSize = in.readInt();
      List<Map<String, String>> list = new ArrayList<Map<String, String>>( listSize );

      for( int i = 0; i < listSize; i++ )
        {
        int mapSize = in.readInt();

        Map<String, String> map = new HashMap<String, String>( mapSize );

        for( int j = 0; j < mapSize; j++ )
          map.put( in.readUTF(), readStringAsObject( in ) );

        list.add( map );
        }

      return list;
      }
    finally
      {
      if( in != null )
        in.close();
      }
    }

  private static String readStringAsObject( ObjectInputStream in ) throws IOException
    {
    try
      {
      return (String) in.readObject();
      }
    catch( ClassNotFoundException exception )
      {
      throw new FlowException( "could not read string", exception );
      }
    }

  /**
   * This method serializes the given Object instance and retunrs a String Base64 representation.
   *
   * @param object to be serialized
   * @return String
   */
  public static String serializeBase64( Object object ) throws IOException
    {
    return serializeBase64( object, true );
    }

  public static String serializeBase64( Object object, boolean compress ) throws IOException
    {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    ObjectOutputStream out = new ObjectOutputStream( compress ? new GZIPOutputStream( bytes ) : bytes );

    try
      {
      out.writeObject( object );
      }
    finally
      {
      out.close();
      }

    return new String( Base64.encodeBase64( bytes.toByteArray() ) );
    }

  /**
   * This method deserializes the Base64 encoded String into an Object instance.
   *
   * @param string
   * @return an Object
   */
  public static Object deserializeBase64( String string ) throws IOException
    {
    return deserializeBase64( string, true );
    }

  public static Object deserializeBase64( String string, boolean decompress ) throws IOException
    {
    if( string == null || string.length() == 0 )
      return null;

    ObjectInputStream in = null;

    try
      {
      ByteArrayInputStream bytes = new ByteArrayInputStream( Base64.decodeBase64( string.getBytes() ) );

      in = new ObjectInputStream( decompress ? new GZIPInputStream( bytes ) : bytes )
      {
      @Override
      protected Class<?> resolveClass( ObjectStreamClass desc ) throws IOException, ClassNotFoundException
        {
        try
          {
          return Class.forName( desc.getName(), false, Thread.currentThread().getContextClassLoader() );
          }
        catch( ClassNotFoundException exception )
          {
          return super.resolveClass( desc );
          }
        }
      };

      return in.readObject();
      }
    catch( ClassNotFoundException exception )
      {
      throw new FlowException( "unable to deserialize data", exception );
      }
    finally
      {
      if( in != null )
        in.close();
      }
    }

  public static Class findMainClass( Class defaultType )
    {
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

    for( StackTraceElement stackTraceElement : stackTrace )
      {
      if( stackTraceElement.getMethodName().equals( "main" ) && !stackTraceElement.getClassName().startsWith( "org.apache.hadoop" ) )
        {
        try
          {
          LOG.info( "resolving application jar from found main method on: {}", stackTraceElement.getClassName() );

          return Thread.currentThread().getContextClassLoader().loadClass( stackTraceElement.getClassName() );
          }
        catch( ClassNotFoundException exception )
          {
          LOG.warn( "unable to load class while discovering application jar: {}", stackTraceElement.getClassName(), exception );
          }
        }
      }

    LOG.info( "using default application jar, may cause class not found exceptions on the cluster" );

    return defaultType;
    }

  public static Map<String, String> getConfig( JobConf defaultConf, JobConf updatedConf )
    {
    Map<String, String> configs = new HashMap<String, String>();

    for( Map.Entry<String, String> entry : updatedConf )
      configs.put( entry.getKey(), entry.getValue() );

    for( Map.Entry<String, String> entry : defaultConf )
      {
      if( entry.getValue() == null )
        continue;

      String updatedValue = configs.get( entry.getKey() );

      // if both null, lets purge from map to save space
      if( updatedValue == null && entry.getValue() == null )
        configs.remove( entry.getKey() );

      // if the values are the same, lets also purge from map to save space
      if( updatedValue != null && updatedValue.equals( entry.getValue() ) )
        configs.remove( entry.getKey() );

      configs.remove( "mapred.working.dir" );
      }

    return configs;
    }

  public static JobConf[] getJobConfs( JobConf job, List<Map<String, String>> configs )
    {
    JobConf[] jobConfs = new JobConf[ configs.size() ];

    for( int i = 0; i < jobConfs.length; i++ )
      jobConfs[ i ] = mergeConf( job, configs.get( i ), false );

    return jobConfs;
    }

  public static JobConf mergeConf( JobConf job, Map<String, String> config, boolean directly )
    {
    JobConf currentConf = directly ? job : new JobConf( job );

    for( String key : config.keySet() )
      {
      LOG.debug( "merging key: {} value: {}", key, config.get( key ) );

      currentConf.set( key, config.get( key ) );
      }

    return currentConf;
    }

  public static JobConf removePropertiesFrom( JobConf jobConf, String... keys )
    {
    Map<Object, Object> properties = createProperties( jobConf );

    for( String key : keys )
      properties.remove( key );

    return createJobConf( properties, null );
    }

  public static boolean removeStateFromDistCache( JobConf conf, String path ) throws IOException
    {
    return new Hfs( new TextLine(), path ).deleteResource( conf );
    }

  public static String writeStateToDistCache( JobConf conf, String id, String stepState )
    {
    LOG.info( "writing step state to dist cache, too large for job conf, size: {}", stepState.length() );

    String statePath = Hfs.getTempPath( conf ) + "/step-state-" + id;

    Hfs temp = new Hfs( new TextLine(), statePath, SinkMode.REPLACE );

    try
      {
      TupleEntryCollector writer = temp.openForWrite( new HadoopFlowProcess( conf ) );

      writer.add( new Tuple( stepState ) );

      writer.close();
      }
    catch( IOException exception )
      {
      throw new FlowException( "unable to write step state to Hadoop FS: " + temp.getIdentifier() );
      }

    URI uri = new Path( statePath ).toUri();
    DistributedCache.addCacheFile( uri, conf );

    LOG.info( "using step state path: {}", uri );

    return statePath;
    }

  public static String readStateFromDistCache( JobConf jobConf, String id ) throws IOException
    {
    Path[] files = DistributedCache.getLocalCacheFiles( jobConf );

    Path stepStatePath = null;

    for( Path file : files )
      {
      if( !file.toString().contains( "step-state-" + id ) )
        continue;

      stepStatePath = file;
      break;
      }

    if( stepStatePath == null )
      throw new FlowException( "unable to find step state from distributed cache" );

    LOG.info( "reading step state from local path: {}", stepStatePath );

    Hfs temp = new Lfs( new TextLine( new Fields( "line" ) ), stepStatePath.toString() );

    TupleEntryIterator reader = null;

    try
      {
      reader = temp.openForRead( new HadoopFlowProcess( jobConf ) );

      if( !reader.hasNext() )
        throw new FlowException( "step state path is empty: " + temp.getIdentifier() );

      return reader.next().getString( 0 );
      }
    catch( IOException exception )
      {
      throw new FlowException( "unable to find state path: " + temp.getIdentifier(), exception );
      }
    finally
      {
      reader.close();
      }
    }
  }
