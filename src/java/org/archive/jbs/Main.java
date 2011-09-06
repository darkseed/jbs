/*
 * Copyright 2010 Internet Archive
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.archive.jbs;

import java.io.*;
import java.util.*;

import org.apache.hadoop.conf.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.mapred.lib.*;
import org.apache.hadoop.util.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.nutch.parse.ParseData;
import org.apache.nutch.parse.ParseText;
import org.apache.nutch.parse.Outlink;
import org.apache.nutch.metadata.Metadata;

/** 
 * Command-line driver and MapReduce code for processing full-text
 * documents, currently originating from a NutchWAX import action.
 *
 * This class can be used to merge multiple NutchWAX segments, along
 * with CDX info to produce a single merged/deduped collection of
 * Document objects.
 *
 * This class can take that merged/deduped collection of Documents
 * and write them to various destinations:
 *   Hadoop file  -- stores the merge/deduped set in a Hadoop file
 *   Lucene index -- builds a full-text Lucene index of the Docs
 *   Solr index   -- pushes the Documents to a Solr server.
 *
 * NOTE: Previously, this class was called 'Indexer', but once it
 * gained the ability to simply store the merge/deduped set of
 * Documents in a Hadoop file, the name needed changed.
 */
public class Main extends Configured implements Tool
{
  public static final Log LOG = LogFactory.getLog(Main.class);

  /**
   * Mapper that handles text files of various formats, primarily CDX and "revisit" files.
   */
  public static class TextMapper extends MapReduceBase implements Mapper<LongWritable, Text, Text, Text>
  {
    private Text outputKey   = new Text( );
    private Text outputValue = new Text( );
    
    public void map( LongWritable key, Text value, OutputCollector<Text,Text> output, Reporter reporter )
      throws IOException
    {
      String line = value.toString().trim();
      
      if ( line.length() == 0 ) return ;
      
      switch ( line.charAt(0) )
        {
        case '{':
          mapJSON( line, output, reporter );
          break;
        case '#':
          // Comment, skip it.
          break;
        default:
          mapCDX( line, output, reporter );
          break;
        }
    }

    private void mapJSON( String line, OutputCollector<Text,Text> output, Reporter reporter )
      throws IOException
    {
      Document doc;
      try
        {
          doc = new Document( line );
        }
      catch ( IOException ioe )
        {
          LOG.warn( "Malformed JSON line: " + line );
          return ;
        }
      
      String key = doc.get( "_key" );
      if ( key.length() == 0 )
        {
          String url    = doc.get( "url"    );
          String digest = doc.get( "digest" );
          
          if ( (url.length() != 0) && (digest.length() != 0) )
            {
              key = url + " " + digest;
            }
          else
            {
              LOG.warn( "Missing url or digest, skipping: " + line );
            }
        }
      
      outputKey  .set( key );
      outputValue.set( doc.toString() );
      output.collect( outputKey, outputValue );
    }

    private void mapCDX( String line, OutputCollector<Text,Text> output, Reporter reporter )
      throws IOException
    {
      String[] fields = line.split( "\\s+" );

      if ( fields.length != 9 )
        {
          LOG.warn( "Malformed CDX line, numFields=" + fields.length + " : " + line );
          return ;
        }

      // Skip DNS records.
      if ( fields[0].startsWith("dns:") ) return ;

      Document doc = new Document( );
      doc.set( "url" ,   fields[0] );
      doc.set( "date",   fields[1] );
      doc.set( "digest", "sha1:" + fields[5] );
      
      outputKey  .set( fields[0] + " sha1:" + fields[5] );
      outputValue.set( doc.toString() );
      
      output.collect( outputKey, outputValue );
    }

  }

  /**
   * Mapper that can handle Writables from Nutch(WAX) segments.
   */
  public static class NutchMapper extends MapReduceBase implements Mapper<Text, Writable, Text, Text>
  {
    private Text outputValue = new Text();

    public void map( Text key, Writable value, OutputCollector<Text, Text> output, Reporter reporter)
      throws IOException
    {
      Document doc = new Document( );

      if ( value instanceof ParseData )
        {
          ParseData pd = (ParseData) value;

          doc.set( "title", pd.getTitle( ) );
          
          Metadata meta = pd.getContentMeta( );
          for ( String name : new String[] { "url", "digest", "length", "collection", "boiled", "date", "type", "keywords", "description" } )
            {
              doc.set( name, meta.get( name ) );
            }

          for ( Outlink outlink : pd.getOutlinks( ) )
            {
              doc.addLink( outlink.getToUrl( ), outlink.getAnchor( ) );
            }
        }
      else if ( value instanceof ParseText )
        {
          doc.set( "content_parsed", value.toString() );
        }
      else
        {
          // Weird
          System.out.println( "NutchMapper unknown value type: " + value.getClass( ) );
          return ;
        }
      outputValue.set( doc.toString() );

      output.collect( key, outputValue );
    }
  }
  
  public static class Reduce extends MapReduceBase implements Reducer<Text, Text, Text, Text> 
  {
    private Text outputValue = new Text();

    public void reduce( Text key, Iterator<Text> values, OutputCollector<Text, Text> output, Reporter reporter)
      throws IOException
    {
      // If no values, then skip it.
      if ( ! values.hasNext( ) ) return ;
      
      // Create a Document of the first JSON value.
      Document doc = new Document( values.next().toString() );

      while ( values.hasNext( ) )
        {
          doc.merge( new Document( values.next().toString() ) );
        }
      
      outputValue.set( doc.toString() );
      
      output.collect( key, outputValue );
    }
  }
  
  public static void main(String[] args) throws Exception
  {
    int result = ToolRunner.run( new JobConf(Main.class), new Main(), args );

    System.exit( result );
  }

  public int run( String[] args ) throws Exception
  {
    if ( args.length < 2 )
      {
        System.err.println( "jbs.Main <output> <input>..." );
        return 1;
      }
      
    JobConf conf = new JobConf( getConf(), Main.class);
    
    conf.setOutputKeyClass(Text.class);
    conf.setOutputValueClass(Text.class);
    
    conf.setCombinerClass(Reduce.class);
    conf.setReducerClass(Reduce.class);
    
    // Choose the outputformat to either merge or index the records
    //
    // org.archive.jbs.lucene.LuceneOutputFormat
    //    - builds local Lucene index
    //
    // org.archive.jbs.solr.SolrOutputFormat
    //    - sends documents to remote Solr server
    //
    // org.apache.hadoop.mapred.MapFileOutputFormat
    //    - writes merged documents to Hadoop MapFile
    conf.setOutputFormat( (Class) Class.forName( conf.get( "jbs.outputformat.class", "org.apache.hadoop.mapred.MapFileOutputFormat" ) ) );
    
    // Set the Hadoop job name to incorporate the output format name.
    String formatName = conf.getOutputFormat().getClass().getName();
    conf.setJobName( "jbs.Main " + formatName.substring( formatName.lastIndexOf('.') != -1 ? (formatName.lastIndexOf('.') + 1) : 0 ) );

    // Add the input paths as either NutchWAX segment directories or
    // text .dup files.
    for ( int i = 1; i < args.length ; i++ )
      {
        Path p = new Path( args[i] );

        // Expand any file globs and then check each matching path
        FileStatus[] files = FileSystem.get( conf ).globStatus( p );

        for ( FileStatus file : files )
          {
            if ( file.isDir( ) )
              {
                // If it's a directory, then check if it is a Nutch segment, otherwise treat as a SequenceFile.
                if ( p.getFileSystem( conf ).exists( new Path( file.getPath(), "parse_data" ) ) )
                  {
                    LOG.info( "Input NutchWax: " + file.getPath() );
                    MultipleInputs.addInputPath( conf, new Path( file.getPath(), "parse_data" ), SequenceFileInputFormat.class, NutchMapper.class );
                    MultipleInputs.addInputPath( conf, new Path( file.getPath(), "parse_text" ), SequenceFileInputFormat.class, NutchMapper.class );
                  }
                else
                  {
                    // Assume it's a SequenceFile of JSON-encoded Documents.
                    LOG.info( "Input Document: " + file.getPath() );
                    MultipleInputs.addInputPath( conf, file.getPath(), SequenceFileInputFormat.class, IdentityMapper.class );
                  }
              }
            else 
              {
                // Not a directory, assume it's a text file, either CDX or property specifications.
                LOG.info( "Input TextFile: " + file.getPath() );
                MultipleInputs.addInputPath( conf, file.getPath(), TextInputFormat.class, TextMapper.class );
              }
          }
      }

    FileOutputFormat.setOutputPath(conf, new Path(args[0]));
    
    JobClient.runJob(conf);
    
    return 0;
  }

}
