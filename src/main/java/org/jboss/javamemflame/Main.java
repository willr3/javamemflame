/*
 * JVM agent to track memory allocations
 *
 * Copyright (C) 2019 Jesper Pedersen <jesper.pedersen@comcast.net>
 */
package org.jboss.javamemflame;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Main class that creates the meminfo-pid.txt file
 */
public class Main
{
   /**
    * Open a file
    *
    * @param p The path of the file
    * @return The file
    */
   private static BufferedWriter openFile(Path p) throws Exception
   {
      BufferedWriter bw = Files.newBufferedWriter(p,
         StandardOpenOption.CREATE,
         StandardOpenOption.WRITE,
         StandardOpenOption.TRUNCATE_EXISTING);
      return bw;
   }

   /**
    * Append data to a file
    *
    * @param bw The file
    * @param s  The string
    */
   private static void append(BufferedWriter bw, String s) throws Exception
   {
      bw.write(s, 0, s.length());
      bw.newLine();
   }

   /**
    * Close a file
    *
    * @param bw The file
    */
   private static void closeFile(BufferedWriter bw) throws Exception
   {
      bw.flush();
      bw.close();
   }

   /**
    * Translate from byte code name to human readable name
    *
    * @param input The input
    * @return Human readable
    */
   private static String translate(String input)
   {
      int array = 0;
      int i = 0;

      StringBuilder sb = new StringBuilder();

      while (input.charAt(i) == '[')
      {
         array++;
         i++;
      }

      if (input.charAt(i) == 'Z')
      {
         sb.append("boolean");
      }
      else if (input.charAt(i) == 'B')
      {
         sb.append("byte");
      }
      else if (input.charAt(i) == 'C')
      {
         sb.append("char");
      }
      else if (input.charAt(i) == 'D')
      {
         sb.append("double");
      }
      else if (input.charAt(i) == 'F')
      {
         sb.append("float");
      }
      else if (input.charAt(i) == 'I')
      {
         sb.append("int");
      }
      else if (input.charAt(i) == 'J')
      {
         sb.append("long");
      }
      else if (input.charAt(i) == 'S')
      {
         sb.append("short");
      }
      else if (input.charAt(i) == 'L')
      {
         sb.append(input.substring(i + 1));
      }
      else
      {
         sb.append(input.substring(i));
      }

      for (int array_counter = 0; array_counter < array; array_counter++)
      {
         sb.append("[]");
      }

      return sb.toString();
   }

   /**
    * Sort the map by value
    *
    * @param m The unsorted map
    * @return The sorted map
    */
   private static Map<String, Long> sortByValue(Map<String, LongAdder> m)
   {
      List<Map.Entry<String, LongAdder>> l = new LinkedList<>(m.entrySet());
      Collections.sort(l, new Comparator<Map.Entry<String, LongAdder>>()
      {
         public int compare(Map.Entry<String, LongAdder> o1, Map.Entry<String, LongAdder> o2)
         {
            return Long.compare(o2.getValue().longValue(), o1.getValue().longValue());
         }
      });
      Map<String, Long> sorted = new LinkedHashMap<>();
      for (Map.Entry<String, LongAdder> entry : l)
      {
         sorted.put(entry.getKey(), entry.getValue().longValue());
      }
      return sorted;
   }

   /**
    * main
    *
    * @parameter args The program arguments
    */
   public static void main(String[] args)
   {
      try
      {
         if (args == null || args.length < 1)
         {
            System.out.println("javamemflame: Recording flamegraph data for Java memory allocations");
            System.out.println("");
            System.out.println("Usage: java -jar javamemflame.jar <file_name> [include[,include]*]");

         }

         String file = args[0];
         Path path = Paths.get(file);
         long pid = 0;
         final Set<String> includes = new HashSet<>();
         final ConcurrentHashMap<String, LongAdder> allocs = new ConcurrentHashMap<>();
         Function<RecordedEvent, Runnable> makeRunnable = (re) -> () ->
         {
            String eventName = re.getEventType().getName();

            if ("jdk.ObjectAllocationInNewTLAB".equals(eventName) ||
               "jdk.ObjectAllocationOutsideTLAB".equals(eventName))
            {
               if (re.hasField("stackTrace") && re.hasField("objectClass") && re.hasField("allocationSize"))
               {
                  RecordedStackTrace st = (RecordedStackTrace) re.getValue("stackTrace");

                  if (st != null)
                  {
                     List<RecordedFrame> lrf = st.getFrames();
                     if (lrf != null && lrf.size() > 0)
                     {
                        StringBuilder sb = new StringBuilder();
                        sb.append("java;");

                        for (int i = lrf.size() - 1; i >= 0; i--)
                        {
                           RecordedFrame rf = st.getFrames().get(i);
                           RecordedMethod rm = rf.getMethod();
                           RecordedClass rc = rm.getType();
                           sb.append(rc.getName().replace('.', '/'));
                           sb.append(":.");
                           sb.append(rm.getName());
                           sb.append(";");
                        }

                        RecordedClass rc = (RecordedClass) re.getValue("objectClass");
                        sb.append(translate(rc.getName()));

                        String entry = sb.toString();

                        if (includes.isEmpty() || includes.stream().filter(include -> entry.contains(include)).findFirst().orElse(null) != null)
                        {
                           allocs.putIfAbsent(entry, new LongAdder());
                           allocs.get(entry).add(re.getLong("allocationSize"));
                        }
                     }
                  }
               }
            }
         };

         if (file.indexOf("-") != -1 && file.indexOf(".") != -1)
         {
            pid = Long.valueOf(file.substring(file.indexOf("-") + 1, file.indexOf(".")));
         }

         BufferedWriter writer = openFile(Paths.get("mem-info-" + pid + ".txt"));

         if (args.length > 1)
         {
            StringTokenizer st = new StringTokenizer(args[1], ",");
            while (st.hasMoreTokens())
            {
               String include = st.nextToken();
               include = include.replace('.', '/');
               includes.add(include);
            }
         }

         RecordingFile rcf = new RecordingFile(path);
         BlockingQueue queue = new ArrayBlockingQueue(2 * Runtime.getRuntime().availableProcessors());
         ThreadPoolExecutor executor = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(),
            2 * Runtime.getRuntime().availableProcessors(),
            30, TimeUnit.MINUTES,
            queue,
            new ThreadPoolExecutor.CallerRunsPolicy());

         while (rcf.hasMoreEvents())
         {
            RecordedEvent re = rcf.readEvent();
            String eventName = re.getEventType().getName();

            if ("jdk.ObjectAllocationInNewTLAB".equals(eventName) ||
               "jdk.ObjectAllocationOutsideTLAB".equals(eventName))
            {
               executor.submit(makeRunnable.apply(re));
            }
         }
         executor.shutdown();
         executor.awaitTermination(24, TimeUnit.HOURS);
         for (Map.Entry<String, Long> entry : sortByValue(allocs).entrySet())
         {
            append(writer, entry.getKey() + " " + entry.getValue());
         }

         rcf.close();
         closeFile(writer);
      } catch (Exception e)
      {
         System.err.println(e.getMessage());
         e.printStackTrace();
      }
   }
}
