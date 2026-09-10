import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

// Parse only: no symbol attribution, annotation processing or class generation.
var compiler = ToolProvider.getSystemJavaCompiler();
var diagnostics = new DiagnosticCollector<JavaFileObject>();
var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8);
var paths = new ArrayList<java.io.File>();
try (var stream = Files.walk(Path.of("src"))) { stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> paths.add(p.toFile())); }
var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "--release", "21"), null, manager.getJavaFileObjectsFromFiles(paths));
try { for (var unit : task.parse()) {} } catch (Exception exception) { System.out.println("PARSE FAILURE: " + exception); }
var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
errors.forEach(System.out::println);
System.out.println("Java syntax parse: files=" + paths.size() + ", errors=" + errors.size());
manager.close();
/exit
