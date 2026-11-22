package conformance;

import com.github.forax.orvet.ast.ASTBuilder;
import com.github.forax.orvet.ast.Script;
import com.github.forax.orvet.ast.SyntaxErrorException;
import com.github.forax.orvet.ast.UnsupportedSyntaxException;
import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.engine.ScopeAnalyzer;
import com.github.forax.orvet.rt.ModuleContext;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// failsafe maven plugin is not yet activate on this repository
/// You have to run this conformance test by hand (this is the early days, do not expect a miracle)
public final class ConformanceTestIT {

  private static final Path CONFORMANCE_DIR = Path.of("target/test-classes/");
  private static final List<String> CONFORMANCE_FOLDERS = List.of("micropython/basics", "beginner");

  private static Path toGolden(Path pyFile) {
    var goldenFileName = pyFile.getFileName().toString().replace(".py", ".golden");
    return pyFile.resolveSibling(goldenFileName);
  }

  public record TestPair(Path pyFile, String source, String goldenSource, Script script) {
    @Override
    public String toString() {
      return CONFORMANCE_DIR.relativize(pyFile).toString();
    }
  }

  private static TestPair createTestPair(Path pyFile) {
    try {
      var source = Files.readString(pyFile);
      var script = ASTBuilder.createScript(source);
      ScopeAnalyzer.analyze(script);

      var goldenFile = toGolden(pyFile);
      var goldenSource = Files.readString(goldenFile);
      return new TestPair(pyFile, source, goldenSource, script);
    } catch (IOException e) {
      throw new IOError(e);
    } catch(SyntaxErrorException | UnsupportedSyntaxException e) {
      return null;
    }
  }

  /** Provides each .py file that has a matching .golden file. */
  static Stream<TestPair> pyFiles() {
    return CONFORMANCE_FOLDERS.stream()
        .flatMap(f -> {
          var dir = CONFORMANCE_DIR.resolve(f);
          return Files.exists(dir) ? Stream.of(dir) : Stream.empty();
        })
        .flatMap(dir-> {
          try {
            return Files.list(dir);
          } catch (IOException e) {
            throw new IOError(e);
          }
        })
        .filter(p -> p.toString().endsWith(".py"))
        .flatMap(p -> Stream.ofNullable(createTestPair(p)))
        .sorted(Comparator.comparing(TestPair::pyFile));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pyFiles")
  public void compareWithGolden(TestPair testPair) throws IOException {
    var pyFile = testPair.pyFile;
    var source = testPair.source;
    var goldenSource = testPair.goldenSource;

    String actual;
    var originalOut = System.out;
    try (var out = new ByteArrayOutputStream();
         var capture = new PrintStream(out)) {
      System.setOut(capture);
      //System.setErr(capture);

      var script = ASTBuilder.createScript(source);

      // Execute the source with ula4j
      try {
        var context = new ModuleContext();
        Engine.run(context, script);
      } catch (UnsupportedSyntaxException | UnimplementedFeatureError e) {
        Assumptions.assumeTrue(false, e.getMessage());
        return;
      }

      // Capture the result
      actual = out.toString();
    } finally {
      System.setOut(originalOut);
      //System.setErr(originalErr);
    }

    // 4. Compare
    assertEquals(goldenSource, actual,
        "Output mismatch for " + pyFile.getFileName());
  }
}