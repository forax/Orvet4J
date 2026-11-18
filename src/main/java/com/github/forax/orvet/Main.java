
package com.github.forax.orvet;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.ModuleContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class Main {
  private Main() {
    throw new AssertionError();
  }

  private static boolean isHelp(String arg) {
    return arg.equals("--help") || arg.equals("-h");
  }

  private static void printUsage() {
    System.out.println("""
        Orvet4J - a lightweight Python subset running on the JVM

        Usage:
          orvet <script.py>          Run an Orvet script
          orvet --help               Display this help
          orvet --jmh [options]      Run JMH benchmarks

        Examples:
          orvet hello.py
          orvet --jmh
          orvet --jmh SumLoopBenchmark
          orvet --jmh SumLoopBenchmark -wi 3 -i 5 -f 2
        """);
  }

  private static void runScript(Path path) throws IOException {
    var source = Files.readString(path);
    var context = new ModuleContext();
    Engine.run(context, source);
  }

  private static void runJMH(String[] args) throws IOException {
    org.openjdk.jmh.Main.main(args);
  }

  static void main(String[] args) {
    if (args.length == 0 || isHelp(args[0])) {
      printUsage();
      return;
    }

    try {
      switch (args[0]) {
        case "--jmh" -> runJMH(Arrays.copyOfRange(args, 1, args.length));
        default -> runScript(Path.of(args[0]));
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("orvet: " + e.getMessage());
      System.exit(1);
    }
  }
}
