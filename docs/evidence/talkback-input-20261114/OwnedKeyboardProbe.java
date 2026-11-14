import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Disposable shell-UID input diagnostic. No accessibility service, app data, root, or UiAutomation. */
public final class OwnedKeyboardProbe {
    // Installed NDK27.1 linux/uhid.h: packed uhid_event/create2/input2, HID_MAX_DESCRIPTOR_SIZE=4096.
    static final int EVENT_SIZE=4376;
    static final byte[] DESCRIPTOR=new byte[]{
        0x05,0x01,0x09,0x06,(byte)0xA1,0x01,0x05,0x07,0x19,(byte)0xE0,0x29,(byte)0xE7,
        0x15,0x00,0x25,0x01,0x75,0x01,(byte)0x95,0x08,(byte)0x81,0x02,
        (byte)0x95,0x01,0x75,0x08,(byte)0x81,0x01,
        (byte)0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,(byte)0x81,0x00,(byte)0xC0};
    final FileDescriptor fd;
    volatile boolean running=true;
    final CountDownLatch opened=new CountDownLatch(1);
    OwnedKeyboardProbe() throws Exception {fd=Os.open("/dev/uhid",OsConstants.O_RDWR,0);}
    static ByteBuffer event(int type) {ByteBuffer b=ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);b.putInt(type);return b;}
    synchronized void send(ByteBuffer b) throws Exception {
        byte[] bytes=b.array();if(Os.write(fd,bytes,0,bytes.length)!=bytes.length)throw new IOException("Short UHID write");
    }
    void create() throws Exception {
        ByteBuffer b=event(11);b.put("whereday-owned-keyboard-probe".getBytes(StandardCharsets.US_ASCII));b.position(260);
        b.putShort((short)DESCRIPTOR.length).putShort((short)3); // BUS_USB, synthetic generic vendor/product 0.
        b.putInt(0).putInt(0).putInt(1).putInt(0);if(b.position()!=280)throw new AssertionError("CREATE2 packing");b.put(DESCRIPTOR);send(b);
        Thread reader=new Thread(()->{
            byte[] bytes=new byte[EVENT_SIZE];
            try {while(running) {
                int count=Os.read(fd,bytes,0,bytes.length);if(count<4)throw new IOException("Short UHID read");
                ByteBuffer message=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int type=message.getInt();
                System.out.println("KERNEL event="+type);System.out.flush();
                if(type==4)opened.countDown(); // UHID_OPEN: kernel consumer has opened this device.
                if(type==9)send(event(10).putInt(message.getInt()).putShort((short)95).putShort((short)0));
                if(type==13)send(event(14).putInt(message.getInt()).putShort((short)95));
            }}catch(Exception error){if(running)error.printStackTrace();}
        },"owned-uhid-reader");reader.setDaemon(true);reader.start();
        if(!opened.await(10,TimeUnit.SECONDS))throw new IOException("Kernel did not open owned UHID keyboard");
        System.out.println("READY uid="+Os.getuid()+" descriptorBytes="+DESCRIPTOR.length);System.out.flush();
    }
    void report(int modifier,int key) throws Exception {
        ByteBuffer b=event(12);b.putShort((short)8).put((byte)modifier).put((byte)0).put((byte)key);send(b);
    }
    void chord(int modifier,int key) throws Exception {
        try {report(modifier,0);Thread.sleep(60);report(modifier,key);Thread.sleep(120);report(modifier,0);Thread.sleep(60);}
        finally {report(0,0);}
    }
    void command(String name) throws Exception {
        switch(name) {
            case "alt-right":chord(4,0x4f);break;
            case "alt-left":chord(4,0x50);break;
            case "read-top":chord(5,0x28);break; // left Ctrl + left Alt + Enter.
            case "menu":chord(4,0x2c);break;
            case "tab":chord(0,0x2b);break;
            case "enter":chord(0,0x28);break;
            default:throw new IllegalArgumentException("Unrecognized diagnostic command");
        }
        System.out.println("SENT "+name);System.out.flush();
    }
    public static void main(String[] args) throws Exception {
        OwnedKeyboardProbe keyboard=new OwnedKeyboardProbe();
        Thread deadline=new Thread(()->{try{Thread.sleep(300000);}catch(InterruptedException ignored){}System.exit(124);},"owned-five-minute-limit");deadline.setDaemon(true);deadline.start();
        try {
            keyboard.create();BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.US_ASCII));
            String line;while((line=input.readLine())!=null && !line.equals("quit"))keyboard.command(line);
        } finally {
            try{keyboard.report(0,0);}finally{keyboard.running=false;try{keyboard.send(event(1));}finally{Os.close(keyboard.fd);}}
            System.out.println("DESTROYED");
        }
    }
}
