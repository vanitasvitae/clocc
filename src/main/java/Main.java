import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.mam.MamManager;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatException;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.omemo.OmemoConfiguration;
import org.jivesoftware.smackx.omemo.OmemoManager;
import org.jivesoftware.smackx.omemo.exceptions.CannotEstablishOmemoSessionException;
import org.jivesoftware.smackx.omemo.exceptions.CorruptedOmemoKeyException;
import org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException;
import org.jivesoftware.smackx.omemo.internal.CachedDeviceList;
import org.jivesoftware.smackx.omemo.internal.ClearTextMessage;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.listener.OmemoMessageListener;
import org.jivesoftware.smackx.omemo.listener.OmemoMucMessageListener;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoSession;
import org.jivesoftware.smackx.omemo.util.KeyUtil;
import org.jivesoftware.smackx.pubsub.PubSubManager;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.stringprep.XmppStringprepException;
import org.whispersystems.libsignal.IdentityKey;

import java.io.File;
import java.security.Security;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;


/**
 * Test
 * Created by vanitas on 28.11.16.
 */
public class Main {
    private AbstractXMPPConnection connection;
    private OmemoManager omemoManager;

    private int deviceId = 20305655;

    private Main() throws XmppStringprepException {
        //*
        SmackConfiguration.DEBUG = true;
        /*/
        SmackConfiguration.DEBUG = false;
        //*/
        OmemoConfiguration.getInstance().setAddOmemoHintBody(false);
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

        Security.addProvider(new BouncyCastleProvider());

        omemoManager = OmemoManager.getInstanceFor(connection, deviceId);
        SignalFileBasedOmemoStore store = new SignalFileBasedOmemoStore(omemoManager, new File("store"));
        SignalOmemoService signalOmemoService = SignalOmemoService.getInstance();
        signalOmemoService.registerDevice(omemoManager, store);

        connection.setPacketReplyTimeout(10000);
        connection = connection.connect();
        connection.login();

        System.out.println("Logged in. Begin setting up OMEMO...");

        OmemoMessageListener messageListener = (decrypted, message, wrapping, omemoMessageInformation) -> {
            BareJid sender = message.getFrom().asBareJid();
            if (sender != null && decrypted != null) {
                reader.callWidget(LineReader.CLEAR);
                reader.getTerminal().writer().println("\033[34m" + sender + ": " + decrypted + "\033[0m "+(omemoMessageInformation != null ? omemoMessageInformation : ""));
                reader.callWidget(LineReader.REDRAW_LINE);
                reader.callWidget(LineReader.REDISPLAY);
                reader.getTerminal().writer().flush();
            }
        };
        OmemoMucMessageListener mucMessageListener = (multiUserChat, bareJid, s, message, message1, omemoMessageInformation) -> {
            if(multiUserChat != null && bareJid != null && s != null) {
                reader.callWidget(LineReader.CLEAR);
                reader.getTerminal().writer().println("\033[36m"+multiUserChat.getRoom()+": "+bareJid+": "+s+"\033[0m "+(omemoMessageInformation != null ? omemoMessageInformation : ""));
                reader.callWidget(LineReader.REDRAW_LINE);
                reader.callWidget(LineReader.REDISPLAY);
                reader.getTerminal().writer().flush();
            }
        };

        CarbonManager.getInstanceFor(connection).enableCarbons();

        omemoManager.addOmemoMessageListener(messageListener);
        omemoManager.addOmemoMucMessageListener(mucMessageListener);

        Roster roster = Roster.getInstanceFor(connection);
        roster.setSubscriptionMode(Roster.SubscriptionMode.accept_all);

        ChatManager cm = ChatManager.getInstanceFor(connection);
        cm.addChatListener((chat, b) -> chat.addMessageListener((chat1, message) -> {
            if(message.getBody() != null && chat1 != null) {
                System.out.println("Message received: " + chat1.getParticipant().toString() + ": " + message.getBody());
            }
        }));

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
            } else if (line.startsWith("/quit")) {
                scanner.close();
                connection.disconnect(new Presence(Presence.Type.unavailable, "Smack is still alive :D", 100, Presence.Mode.away));
                break;
            } else if (line.startsWith("/add")) {
                String jid = split.length == 4 ? split[1] : null;
                if(jid != null) {
                    BareJid b = JidCreate.bareFrom(jid);
                    roster.createEntry(b, split[2], new String[]{split[3]});
                } else {
                    System.out.println("Usage: /add jid@server nick group");
                }
            } else if(line.startsWith("/remove")) {
                if(split.length == 2) {
                    BareJid b = getJid(split[1]);
                    roster.removeEntry(roster.getEntry(b));
                    System.out.println("Removed contact from roster");
                }
            } else if(line.startsWith("/list")){

                if(split.length == 1) {
                    for (RosterEntry r : roster.getEntries()) {
                        System.out.println(r.getName() + " (" + r.getJid() + ") Can I see? " + r.canSeeHisPresence() + ". Can they see? " + r.canSeeMyPresence() + ". Online? " + roster.getPresence(r.getJid()).isAvailable());
                    }
                    for (EntityBareJid r : mucm.getJoinedRooms()) {
                        System.out.println(r.asBareJid().toString());
                    }
                } else {
                    BareJid jid = getJid(split[1]);
                    try {
                        List<Presence> presences = roster.getAllPresences(jid);
                        for(Presence p : presences) {
                            System.out.println(p.getFrom()+" "+omemoManager.resourceSupportsOmemo(p.getFrom().asDomainFullJidIfPossible()));
                        }
                    } catch (Exception e) {}
                    omemoManager.requestDeviceListUpdateFor(jid);
                    CachedDeviceList list = store.loadCachedDeviceList(jid);
                    if(list == null) {
                        list = new CachedDeviceList();
                    }
                    ArrayList<String> fps = new ArrayList<>();
                    for(int id : list.getActiveDevices()) {
                        OmemoDevice d = new OmemoDevice(jid, id);
                        IdentityKey idk = store.loadOmemoIdentityKey(d);
                        if(idk == null) {
                            try {
                                omemoManager.buildSessionWith(d);
                                idk = store.loadOmemoIdentityKey(d);
                            } catch (CannotEstablishOmemoSessionException | CorruptedOmemoKeyException e) {
                                System.out.println("Error: "+e.getMessage());
                            }
                        }
                        if(idk != null) {
                            fps.add(KeyUtil.prettyFingerprint(store.keyUtil().getFingerprint(idk)));
                        }
                    }
                    for(int i=0; i<fps.size(); i++) {
                        System.out.println(i+": "+fps.get(i));
                    }
                }
            } else if(line.startsWith("/trust")) {
                if(split.length == 2) {
                    System.out.println("Usage: \n0: Untrusted, 1: Trusted, otherwise: Undecided");
                    BareJid jid = getJid(split[1]);

                    if(jid == null) {
                        continue;
                    }

                    omemoManager.requestDeviceListUpdateFor(jid);
                    CachedDeviceList l = store.loadCachedDeviceList(jid);

                    l.getActiveDevices().stream().filter(i -> i != omemoManager.getDeviceId()).forEach(i -> {
                        OmemoDevice d = new OmemoDevice(jid, i);
                        SignalOmemoSession s = (SignalOmemoSession) store.getOmemoSessionOf(d);
                        if(s.getIdentityKey() == null) {
                            try {
                                System.out.println("Build session...");
                                omemoManager.getFingerprint(d);
                                s = (SignalOmemoSession) store.getOmemoSessionOf(d);
                                System.out.println("Session built.");
                            } catch (CannotEstablishOmemoSessionException e) {
                                e.printStackTrace();
                            }
                        }
                        if (store.isDecidedOmemoIdentity(d, s.getIdentityKey())) {
                            if (store.isTrustedOmemoIdentity(d, s.getIdentityKey())) {
                                System.out.println("Status: Trusted");
                            } else {
                                System.out.println("Status: Untrusted");
                            }
                        } else {
                            System.out.println("Status: Undecided");
                        }
                        System.out.println(KeyUtil.prettyFingerprint(s.getFingerprint()));
                        String decision = scanner.nextLine();
                        if (decision.equals("0")) {
                            store.distrustOmemoIdentity(d, s.getIdentityKey());
                            System.out.println("Identity has been untrusted.");
                        } else if (decision.equals("1")) {
                            store.trustOmemoIdentity(d, s.getIdentityKey());
                            System.out.println("Identity has been trusted.");
                        }
                    });
                }

            } else if(line.startsWith("/purge")) {
                omemoManager.purgeDevices();
                System.out.println("Purge successful.");
            } else if(line.startsWith("/regenerate")) {
                omemoManager.regenerate();
                System.out.println("Regeneration successful.");
            } else if(line.startsWith("/omemo")) {
                if(split.length == 1) {
                } else {
                    BareJid recipient = getJid(split[1]);
                    if (recipient != null) {
                        String message = "";
                        for (int i = 2; i < split.length; i++) {
                            message += split[i] + " ";
                        }
                        Message m = new Message();
                        m.setBody(message.trim());
                        m.setTo(recipient);

                        try {
                            Message e = omemoManager.encrypt(recipient, m);
                            current = cm.createChat(recipient.asEntityJidIfPossible());
                            current.sendMessage(e);
                        } catch (UndecidedOmemoIdentityException e) {
                            System.out.println("There are undecided identities:");
                            for(OmemoDevice d : e.getUntrustedDevices()) {
                                System.out.println(d.toString());
                            }
                        }

                    }
                }
                omemo = true;
            }
            else if(line.startsWith("/mucomemo")) {
                if(split.length >= 3) {
                    BareJid mucJid = getJid(split[1]);
                    if (mucJid != null) {
                        String message = "";
                        for (int i = 2; i < split.length; i++) {
                            message += split[i] + " ";
                        }
                        Message m = new Message();
                        m.setBody(message.trim());
                        MultiUserChat muc = mucm.getMultiUserChat(mucJid.asEntityBareJidIfPossible());
                        List<EntityFullJid> occupants = muc.getOccupants();
                        ArrayList<BareJid> recipients = occupants.stream().map(e ->
                                muc.getOccupant(e.asEntityFullJidIfPossible()).getJid().asBareJid())
                                .collect(Collectors.toCollection(ArrayList::new));
                        Message encrypted = omemoManager.encrypt(recipients, m);
                        muc.sendMessage(encrypted);
                    }
                }
            } else if(line.startsWith("/fingerprint")) {
                String fingerprint = omemoManager.getOurFingerprint();
                System.out.println(KeyUtil.prettyFingerprint(fingerprint));
            } else if(line.startsWith("/help")) {
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
            } else if(line.startsWith("/mam")) {
                MamManager mamManager = MamManager.getInstanceFor(connection);
                MamManager.MamQueryResult result = mamManager.queryArchive(new Date(System.currentTimeMillis()-1000*60*60*24), new Date(System.currentTimeMillis()));
                for(ClearTextMessage d : omemoManager.decryptMamQueryResult(result)) {
                    messageListener.onOmemoMessageReceived(d.getBody(), d.getOriginalMessage(), null, d.getMessageInformation());
                }
                System.out.println("Query finished");
            }
            else if (line.startsWith("/test")) {
                PubSubManager pm = PubSubManager.getInstance(connection, connection.getUser().asBareJid());
                try {
                    pm.getLeafNode("blablasda");
                } catch (Exception e) {
                    System.out.println(e.getClass().getName()+": "+e.getMessage());
                }
            }

            else {
                if(current != null) {
                    if(!omemo) {
                        current.sendMessage(line);
                    } else {
                        Message m = new Message();
                        m.setBody(line.trim());
                        try {
                            Message e = omemoManager.encrypt(current.getParticipant().asEntityBareJid(), m);
                            current.sendMessage(e);
                        } catch (UndecidedOmemoIdentityException e) {
                            System.out.println("There are undecided identities:");
                            for(OmemoDevice d : e.getUntrustedDevices()) {
                                System.out.println(d.toString());
                            }
                        }
                    }
                }
                else
                    System.out.println("please open a chat");
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

    public BareJid getJid(String user) {
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
}
