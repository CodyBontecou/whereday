import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** Verify every payload entry, including META-INF payload, using the JDK's JAR verifier. */
class VerifyBundleSignature {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Expected bundle path and SHA-256 certificate fingerprint");
        }
        int covered = 0;
        var names = new HashSet<String>();
        try (var jar = new JarFile(Path.of(args[0]).toFile(), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!names.add(entry.getName())) throw new SecurityException("Duplicate archive entry");
                if (entry.isDirectory()) continue;
                // These are signing machinery, not payload. Other META-INF files must be signed.
                String name = entry.getName().toUpperCase(java.util.Locale.ROOT);
                boolean signature = name.equals("META-INF/MANIFEST.MF") ||
                    name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)");
                try (InputStream input = jar.getInputStream(entry)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                if (signature) continue;
                var signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1) throw new SecurityException("Unsigned or multiply signed payload");
                var cert = (X509Certificate) signers[0].getSignerCertPath().getCertificates().get(0);
                cert.checkValidity();
                if (cert.getSubjectX500Principal().getName().toLowerCase(java.util.Locale.ROOT).contains("cn=android debug")) {
                    throw new SecurityException("Android debug certificate is not a release upload certificate");
                }
                String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
                if (!fingerprint.equalsIgnoreCase(args[1])) throw new SecurityException("Upload certificate mismatch");
                covered++;
            }
        }
        if (covered == 0) throw new SecurityException("No signed payload");
        System.out.println(covered);
    }
}
