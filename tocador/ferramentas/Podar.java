import org.objectweb.asm.*;
import java.nio.file.*;
/** Remove métodos de uma classe (usado para tirar o toString com lambda do ONNX Runtime). */
public class Podar {
    public static void main(String[] a) throws Exception {
        Path p = Paths.get(a[0]);
        java.util.Set<String> tirar = new java.util.HashSet<String>(java.util.Arrays.asList(a).subList(1, a.length));
        ClassReader r = new ClassReader(Files.readAllBytes(p));
        ClassWriter w = new ClassWriter(0);
        r.accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override public MethodVisitor visitMethod(int acc, String nome, String desc, String sig, String[] exc) {
                return tirar.contains(nome) ? null : super.visitMethod(acc, nome, desc, sig, exc);
            }
            @Override public void visitInnerClass(String n, String o, String i, int acc) { if (!"java/lang/invoke/MethodHandles$Lookup".equals(n)) super.visitInnerClass(n, o, i, acc); }
        }, 0);
        Files.write(p, w.toByteArray());
    }
}
