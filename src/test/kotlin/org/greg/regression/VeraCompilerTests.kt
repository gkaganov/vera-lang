package org.greg.regression;

import io.vavr.collection.HashMap;
import org.greg.VeraCompiler;
import org.greg.lib.classloader.ByteArrayClassLoader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VeraCompilerTests {

    private static final String EOL = System.lineSeparator();
    private static final AtomicLong CLASS_ID = new AtomicLong();

    @Test
    void printBuiltinPrintsToSystemOut() throws Exception {
        var source = """
                    fn testMethod() {
                        print(65)
                    }
                """;
        assertProgramPrints(source, 65 + EOL);
    }

    @Test
    void functionsCanBeCalled() throws Exception {
        var source = """
                    fn testMethod() {
                        print1()
                        print2()
                    }
                    fn print1() {
                        print(1)
                    }
                    fn print2() {
                        print(2)
                    }
                """;
        assertProgramPrints(source, 1 + EOL + 2 + EOL);
    }

    @Test
    void varCanBeBoundAndAccessed() throws Exception {
        var source = """
                    fn testMethod() {
                        var myVar1 = 50
                        var myVar2 = 51
                        print(myVar1)
                        print(myVar2)
                        print(52)
                    }
                """;
        assertProgramPrints(source, 50 + EOL + 51 + EOL + 52 + EOL);
    }

    private void assertProgramPrints(String source, String expectedOutput) throws ReflectiveOperationException {
        var mainMethodName = "testMethod";
        var className = "CompiledTestClass" + CLASS_ID.getAndIncrement();
        var bytecode = new VeraCompiler(className).compile(source);
        var oldOut = System.out;
        var baos = new ByteArrayOutputStream();
        var classLoader = new ByteArrayClassLoader(HashMap.of(className, bytecode));
        var testMethod = classLoader.loadClass(className).getMethod(mainMethodName);
        try {
            System.setOut(new PrintStream(baos));
            testMethod.invoke(null);
        } finally {
            System.setOut(oldOut);
        }
        assertEquals(expectedOutput, baos.toString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unused")
    private void assertProgramReturns(String source, Object expected) throws ReflectiveOperationException {
        var methodName = "testMethod";
        var className = "CompiledTestClass" + System.nanoTime();
        var bytecode = new VeraCompiler(className).compile(source);
        var classLoader = new ByteArrayClassLoader(HashMap.of(className, bytecode));
        var testMethod = classLoader.loadClass(className).getMethod(methodName);
        var actual = testMethod.invoke(null);
        assertEquals(expected, actual);
    }
}
