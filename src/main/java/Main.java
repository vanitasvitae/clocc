import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.carbons.packet.CarbonExtension;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatException;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.omemo.OmemoConfiguration;
import org.jivesoftware.smackx.omemo.OmemoManager;
import org.jivesoftware.smackx.omemo.OmemoMessage;
import org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException;
import org.jivesoftware.smackx.omemo.internal.OmemoCachedDeviceList;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.listener.OmemoMessageListener;
import org.jivesoftware.smackx.omemo.listener.OmemoMucMessageListener;
import org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback;
import org.jivesoftware.smackx.omemo.trust.TrustState;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.stringprep.XmppStringprepException;
import org.whispersystems.libsignal.IdentityKey;


/**
 * Command Line OMEMO Chat Client.
 * This client was developed for testing purposes. It can easily crash for unexpected inputs. Use with that in mind.
 *
 * Created by vanitas on 28.11.16.
 */
public class Main {
    private AbstractXMPPConnection connection;
    private OmemoManager omemoManager;

    private final static File storePath = new File("store");

    private Main() {
        /*
        SmackConfiguration.DEBUG = true;
        /*/
        SmackConfiguration.DEBUG = false;
        //*/
        OmemoConfiguration.setAddOmemoHintBody(false);
    }

    public void start() throws Exception {
        Terminal terminal = TerminalBuilder.terminal();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        String prompt = "> ";

        Scanner scanner = new Scanner(System.in);
        String jidname = null, password = null;
        while(jidname == null) {
            System.out.println("Enter username:");
            jidname = scanner.nextLine();
        }
        while (password == null) {
            System.out.println("Enter password:");
            password = scanner.nextLine();
        }
        connection = new XMPPTCPConnection(jidname, password);

        SignalOmemoService.acknowledgeLicense();
        SignalOmemoService.setup();
        SignalOmemoService service = (SignalOmemoService) SignalOmemoService.getInstance();
        service.setOmemoStoreBackend(new SignalCachingOmemoStore(new SignalFileBasedOmemoStore(storePath)));

        omemoManager = OmemoManager.getInstanceFor(connection);
        omemoManager.setTrustCallback(new OmemoTrustCallback() {
            @Override
            public TrustState getTrust(OmemoDevice device, OmemoFingerprint fingerprint) {
                try {
                    return Main.this.getTrust(omemoManager.getOwnDevice(), device, fingerprint);
                } catch (IOException e) {
                    System.out.println("Could not get Trust of device " + device.toString() + ": " + e.getMessage());
                    return TrustState.undecided;
                }
            }

            @Override
            public void setTrust(OmemoDevice device, OmemoFingerprint fingerprint, TrustState state) {
                try {
                    Main.this.storeTrust(omemoManager.getOwnDevice(), device, fingerprint, state);
                } catch (IOException e) {
                    System.out.println("Could not set Trust of device " + device.toString() + ": " + e.getMessage());
                }
            }
        });
        connection.setReplyTimeout(10000);
        connection = connection.connect();
        connection.login();
        omemoManager.initialize();

        System.out.println("Logged in. Begin setting up OMEMO...");

        OmemoMessageListener messageListener = new OmemoMessageListener() {
            @Override
            public void onOmemoMessageReceived(Stanza stanza, OmemoMessage.Received received) {
                BareJid sender = stanza.getFrom().asBareJid();
                if (received.isKeyTransportMessage()) {
                    return;
                }
                String decryptedBody = received.getBody();
                if (sender != null && decryptedBody != null) {
                    reader.callWidget(LineReader.CLEAR);
                    reader.getTerminal().writer().println("\033[34m" + sender + ": " + decryptedBody);
                    reader.callWidget(LineReader.REDRAW_LINE);
                    reader.callWidget(LineReader.REDISPLAY);
                    reader.getTerminal().writer().flush();
                }
            }

            @Override
            public void onOmemoCarbonCopyReceived(CarbonExtension.Direction direction, Message carbonCopy, Message wrappingMessage, OmemoMessage.Received decryptedCarbonCopy) {

            }
        };
        OmemoMucMessageListener mucMessageListener = (multiUserChat, stanza, received) -> {
            BareJid bareJid = received.getSenderDevice().getJid();
            if (received.isKeyTransportMessage()) {
                return;
            }
            String s = received.getBody();
            if (multiUserChat != null && bareJid != null && s != null) {
                reader.callWidget(LineReader.CLEAR);
                reader.getTerminal().writer().println("\033[36m" + multiUserChat.getRoom() + ": " + bareJid + ": " + s);
                reader.callWidget(LineReader.REDRAW_LINE);
                reader.callWidget(LineReader.REDISPLAY);
                reader.getTerminal().writer().flush();
            }
        };

        // Carbon Copies
        CarbonManager.getInstanceFor(connection).enableCarbons();

        omemoManager.addOmemoMessageListener(messageListener);
        omemoManager.addOmemoMucMessageListener(mucMessageListener);

        // Contact list
        Roster roster = Roster.getInstanceFor(connection);
        roster.setSubscriptionMode(Roster.SubscriptionMode.accept_all);

        // Single Chats
        ChatManager cm = ChatManager.getInstanceFor(connection);
        cm.addChatListener((chat, b) -> chat.addMessageListener((chat1, message) -> {
            if(message.getBody() != null && chat1 != null) {
                System.out.println("Message received: " + chat1.getParticipant().toString() + ": " + message.getBody());
            }
        }));

        // Group Chats
        MultiUserChatManager mucm = MultiUserChatManager.getInstanceFor(connection);
        mucm.setAutoJoinOnReconnect(true);
        mucm.addInvitationListener((xmppConnection, multiUserChat, entityFullJid, s, s1, message, invite) -> {
            try {
                multiUserChat.join(Resourcepart.from("OMEMO"));
                multiUserChat.addMessageListener(message1 -> {
                    System.out.println("MUC: "+message1.getFrom()+": "+message1.getBody());
                });
                System.out.println("Joined Room "+multiUserChat.getRoom().asBareJid().toString());
            } catch (SmackException.NoResponseException | XMPPException.XMPPErrorException | InterruptedException | MultiUserChatException.NotAMucServiceException | SmackException.NotConnectedException | XmppStringprepException e) {
                e.printStackTrace();
            }
        });
        System.out.println("OMEMO setup complete. You can now start chatting.");
        Chat current = null;
        boolean omemo = false;

        // Begin REPL
        while (true) {
            String line = null;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) {
                // Ignore
            } catch (EndOfFileException e) {
                return;
            }
            String [] split = line.split(" ");

            // Send unencrypted chat message
            if(line.startsWith("/chat ")) {
                String l = line.substring("/chat ".length());
                if(l.length() == 0) {
                    System.out.println(current != null ? current.getParticipant() : "null");
                } else {
                    String id = split[1];
                    BareJid jid = getJid(id);
                    if(jid != null) {
                        current = cm.createChat(jid.asEntityJidIfPossible());
                        current.sendMessage(l.substring(id.length() + 1));
                    }
                }
            }

            // Exit the client
            else if (line.startsWith("/quit")) {
                scanner.close();
                connection.disconnect(new Presence(Presence.Type.unavailable, "Smack is still alive :D", 100, Presence.Mode.away));
                break;
            }

            // Add contacts
            else if (line.startsWith("/add")) {
                String jid = split.length == 4 ? split[1] : null;
                if(jid != null) {
                    BareJid b = JidCreate.bareFrom(jid);
                    roster.createEntry(b, split[2], new String[]{split[3]});
                } else {
                    System.out.println("Usage: /add jid@server nick group");
                }
            }

            // Remove contact
            else if(line.startsWith("/remove")) {
                if(split.length == 2) {
                    BareJid b = getJid(split[1]);
                    roster.removeEntry(roster.getEntry(b));
                    System.out.println("Removed contact from roster");
                }
            }

            // List available contacts/groups or device list of selected contact
            else if(line.startsWith("/list")){

                if(split.length == 1) {
                    for (RosterEntry r : roster.getEntries()) {
                        System.out.println(r.getName() + " (" + r.getJid() + ") Can I see? " + r.canSeeHisPresence() + ". Can they see? " + r.canSeeMyPresence() + ". Online? " + roster.getPresence(r.getJid()).isAvailable());
                    }
                    for (EntityBareJid r : mucm.getJoinedRooms()) {
                        System.out.println(r.asBareJid().toString());
                    }
                }

                // List presences of one contact, as well as OMEMO fingerprints
                else {
                    BareJid jid = getJid(split[1]);
                    try {
                        List<Presence> presences = roster.getAllPresences(jid);
                        for(Presence p : presences) {
                            System.out.println(p.getFrom());
                        }
                    } catch (Exception e) {}
                    omemoManager.requestDeviceListUpdateFor(jid);
                    OmemoCachedDeviceList list = service.getOmemoStoreBackend().loadCachedDeviceList(omemoManager.getOwnDevice(), jid);
                    if(list == null) {
                        list = new OmemoCachedDeviceList();
                    }
                    ArrayList<String> fps = new ArrayList<>();
                    for(int id : list.getActiveDevices()) {
                        OmemoDevice d = new OmemoDevice(jid, id);
                        IdentityKey idk = service.getOmemoStoreBackend().loadOmemoIdentityKey(omemoManager.getOwnDevice(), d);
                        if(idk == null) {
                            System.out.println("No identityKey for "+d);
                        } else {
                            OmemoFingerprint fp = service.getOmemoStoreBackend().getFingerprint(omemoManager.getOwnDevice(), d);
                            if (fp != null) {
                                fps.add(fp.blocksOf8Chars());
                            }
                        }
                    }
                    for(int i=0; i<fps.size(); i++) {
                        System.out.println(i+": "+fps.get(i));
                    }
                }
            }

            // Make trust decisions for keys of a user
            else if(line.startsWith("/trust")) {
                if(split.length == 2) {
                    System.out.println("Usage: \n0: Untrusted, 1: Trusted, otherwise: Undecided");
                    BareJid jid = getJid(split[1]);

                    if(jid == null) {
                        continue;
                    }

                    System.out.println(jid);

                    omemoManager.requestDeviceListUpdateFor(jid);


                    for (OmemoDevice device : omemoManager.getDevicesOf(jid)) {
                        OmemoFingerprint fp = omemoManager.getFingerprint(device);

                        if (omemoManager.isDecidedOmemoIdentity(device, fp)) {
                            if (omemoManager.isTrustedOmemoIdentity(device, fp)) {
                                System.out.println("Status: Trusted");
                            } else {
                                System.out.println("Status: Untrusted");
                            }
                        } else {
                            System.out.println("Status: Undecided");
                        }

                        System.out.println(fp.blocksOf8Chars());
                        String decision = scanner.nextLine();
                        if (decision.equals("0")) {
                            omemoManager.distrustOmemoIdentity(device, fp);
                            System.out.println("Identity has been untrusted.");
                        } else if (decision.equals("1")) {
                            omemoManager.trustOmemoIdentity(device, fp);
                            System.out.println("Identity has been trusted.");
                        }
                    }
                }

            }

            // Delete foreign OMEMO devices from own device list
            else if(line.startsWith("/purge")) {
                omemoManager.purgeDeviceList();
                System.out.println("Purge successful.");
//            } else if(line.startsWith("/regenerate")) {
//                omemoManager.regenerateIdentity();
//                System.out.println("Regeneration successful.");
            }

            // Write encrypted OMEMO message to single chat
            else if(line.startsWith("/omemo")) {
                if(split.length == 1) {
                } else {
                    BareJid recipient = getJid(split[1]);
                    if (recipient != null) {
                        String message = "";
                        for (int i = 2; i < split.length; i++) {
                            message += split[i] + " ";
                        }
                        OmemoMessage.Sent encrypted = null;
                        try {
                            encrypted = omemoManager.encrypt(recipient, message.trim());
                        } catch (UndecidedOmemoIdentityException e) {
                            System.out.println("There are undecided identities:");
                            for(OmemoDevice d : e.getUndecidedDevices()) {
                                System.out.println(d.toString());
                            }
                        }
                        if(encrypted != null) {
                            current = cm.createChat(recipient.asEntityJidIfPossible());
                            Message m = new Message();
                            m.addExtension(encrypted.getElement());
                            current.sendMessage(m);
                        }
                    }
                }
                omemo = true;
            }

            // Send encrypted OMEMO message to group chat
            else if(line.startsWith("/mucomemo")) {
                if(split.length >= 3) {
                    BareJid mucJid = getJid(split[1]);
                    if (mucJid != null) {
                        String message = "";
                        for (int i = 2; i < split.length; i++) {
                            message += split[i] + " ";
                        }
                        MultiUserChat muc = mucm.getMultiUserChat(mucJid.asEntityBareJidIfPossible());
                        OmemoMessage.Sent encrypted = null;
                        try {
                            encrypted = omemoManager.encrypt(muc, message.trim());
                        } catch (UndecidedOmemoIdentityException e) {
                            System.out.println("There are undecided identities:");
                            for(OmemoDevice d : e.getUndecidedDevices()) {
                                System.out.println(d.toString());
                            }
                        }

                        if(encrypted != null) {
                            Message m = new Message();
                            m.addExtension(encrypted.getElement());
                            muc.sendMessage(m);
                        }
                    }
                }
            }

            // Display own fingerprint
            else if(line.startsWith("/fingerprint")) {
                OmemoFingerprint fingerprint = omemoManager.getOwnFingerprint();
                System.out.println(fingerprint.blocksOf8Chars());
            }

            // Display help text
            else if(line.startsWith("/help")) {
                if(split.length == 1) {
                    System.out.println("Available options: \n" +
                            "/chat <Nickname/Jid> <Message>: Send a normal unencrypted chat message to a user. \n" +
                            "/omemo <Nickname/Jid> <Message>: Send an OMEMO encrypted message to a user. \n" +
                            "/mucomemo <MUC-Jid> <Message>: Send an OMEMO encrypted message to a group chat. \n" +
                            "/list: List your roster. \n" +
                            "/list <Nickname/Jid>: List all devices of a user. \n" +
                            "/fingerprint: Show your OMEMO fingerprint. \n" +
                            "/purge: Remove all other devices from your list of active devices. \n" +
                            "/regenerate: Create a new OMEMO identity. \n" +
                            "/add <jid> <Nickname> <group>: Add a new contact to your roster. \n" +
                            "/remove <jid>: Remove a contact from your roster. \n" +
                            "/quit: Quit the application.");
                }
//            } else if(line.startsWith("/mam")) {
//                MamManager mamManager = MamManager.getInstanceFor(connection);
//                MamManager.MamQueryResult result = mamManager.queryArchive(new Date(System.currentTimeMillis()-1000*60*60*24), new Date(System.currentTimeMillis()));
//                for(ClearTextMessage d : omemoManager.decryptMamQueryResult(result)) {
//                    messageListener.onOmemoMessageReceived(d.getBody(), d.getOriginalMessage(), null, d.getMessageInformation());
//                }
//                System.out.println("Query finished");
            }

            // Send ratchet update message to repair/forward session with contact
            else if(line.startsWith("/ratchetUpdate")) {
                if(split.length == 2) {
                    BareJid jid = getJid(split[1]);
                    OmemoCachedDeviceList cachedDeviceList = service.getOmemoStoreBackend().loadCachedDeviceList(omemoManager.getOwnDevice(), jid);
                    for(int id : cachedDeviceList.getActiveDevices()) {
                        OmemoDevice d = new OmemoDevice(jid, id);
                        omemoManager.sendRatchetUpdateMessage(d);
                    }
                }
            }

            // If no command is entered, assume chat with contact is still active -> send message
            else {
                if(current != null) {
                    if(!omemo) {
                        current.sendMessage(line);
                    } else {
                        try {
                            OmemoMessage.Sent e = omemoManager.encrypt(current.getParticipant().asEntityBareJid(), line.trim());
                            Message m = new Message();
                            m.addExtension(e.getElement());
                            current.sendMessage(m);
                        } catch (UndecidedOmemoIdentityException e) {
                            System.out.println("There are undecided identities:");
                            for(OmemoDevice d : e.getUndecidedDevices()) {
                                System.out.println(d.toString());
                            }
                        }
                    }
                } else {
                    System.out.println("please open a chat");
                }
            }
        }
    }

    public static void main(String[] args) {
        try {
            Main main = new Main();
            main.start();
        } catch (Exception ignored) {
            ignored.printStackTrace();
        }
    }

    /**
     * Translate Nick to JID.
     *
     * @param user Nick or Jid of a contact as String
     * @return BareJid of the contact or null, if jid cannot be determined.
     */
    private BareJid getJid(String user) {
        Roster roster = Roster.getInstanceFor(connection);
        RosterEntry r = null;
        for(RosterEntry s : roster.getEntries()) {
            if(s.getName() != null && s.getName().equals(user)) {
                r = s;
                break;
            }
        }
        if(r != null) {
            return r.getJid();
        } else {
            try {
                return JidCreate.bareFrom(user);
            } catch (XmppStringprepException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    /**
     * Store a trust decision in persistent storage.
     * This particular method mimics the behaviour of the {@link SignalFileBasedOmemoStore}.
     *
     * @param userDevice our own OMEMO device
     * @param contactsDevice OMEMO device of the contact in question
     * @param fingerprint the contacts devices fingerprint
     * @param trustState the new trust state.
     *
     * @throws IOException IO is dangerous (we write to a file)
     */
    private void storeTrust(OmemoDevice userDevice, OmemoDevice contactsDevice, OmemoFingerprint fingerprint, TrustState trustState)
            throws IOException {
        File target = new File(storePath, "OMEMO_Store" + File.separator +
                userDevice.getJid().toString() + File.separator +
                userDevice.getDeviceId() + File.separator +
                "contacts" + File.separator +
                contactsDevice.getJid().toString() + File.separator +
                contactsDevice.getDeviceId() + File.separator +
                "trust");
        if (!target.exists()) {
            target.getParentFile().mkdirs();
            target.createNewFile();
        }

        try (PrintWriter out = new PrintWriter(target)) {
            out.write(fingerprint.toString() + " " + trustState);
        } catch (FileNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Retrieves a trust decision from persistent storage.
     * If no trust record was found, return undecided.
     * If a trust record for that device with a different fingerprint was found, return untrusted.
     * This particular method mimics the behaviour of the {@link SignalFileBasedOmemoStore}.
     *
     * @param userDevice our own OMEMO device
     * @param contactsDevice OMEMO device of the contact in question
     * @param fingerprint contacts devices fingerprint.
     * @return trust state
     *
     * @throws IOException IO is dangerous (we read from a file)
     */
    private TrustState getTrust(OmemoDevice userDevice, OmemoDevice contactsDevice, OmemoFingerprint fingerprint)
            throws IOException {
        File target = new File(storePath, "OMEMO_Store" + File.separator +
                userDevice.getJid().toString() + File.separator +
                userDevice.getDeviceId() + File.separator +
                "contacts" + File.separator +
                contactsDevice.getJid().toString() + File.separator +
                contactsDevice.getDeviceId() + File.separator +
                "trust");

        if (!target.exists()) {
            return TrustState.undecided;
        }

        try (BufferedReader in = new BufferedReader(new FileReader(target))) {
            String line = in.readLine();
            String[] split = line.split(" ");
            if (split.length != 2) {
                return TrustState.undecided;
            }
            OmemoFingerprint f = new OmemoFingerprint(split[0]);
            TrustState t = TrustState.valueOf(split[1]);
            if (f.equals(fingerprint)) {
                return t;
            }
            return TrustState.untrusted;
        } catch (FileNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
