import com.particlesdevs.photoncamera.processing.mcraw.McrawWriter;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ContainerFixture {
    public static void main(String[] args) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(args[1]);
             McrawWriter writer = new McrawWriter(stream.getChannel(),stream,
                     "{\"extraData\":{\"audioSampleRate\":44100,\"audioChannels\":2}}")) {
            byte[] frame = Files.readAllBytes(Path.of(args[0]));
            for (long ts : new long[]{1000000000L,1033333333L})
                writer.writeFrame(ByteBuffer.wrap(frame),ts,"{\"width\":64,\"height\":8,\"compressionType\":7}");
            writer.writeAudio(new short[]{1,-2,3,-4},1005000000L);
            writer.writeGyro(new long[]{1001000000L,1002000000L},
                    new float[]{0.1f,0.4f},new float[]{-0.2f,0.5f},
                    new float[]{0.3f,-0.6f},2);
        }
    }
}
