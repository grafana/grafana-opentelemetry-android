import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

class LockProbe {
    public static void main(String[] args) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(args[0], "rw")) {
            try (FileLock lock = file.getChannel().tryLock()) {
                System.out.println(lock == null ? "LOCKED" : "AVAILABLE");
            }
        }
    }
}
