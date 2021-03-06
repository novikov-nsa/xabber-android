/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.roster;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.xabber.android.R;
import com.xabber.android.data.Application;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.account.AccountItem;
import com.xabber.android.data.account.AccountManager;
import com.xabber.android.data.account.listeners.OnAccountDisabledListener;
import com.xabber.android.data.account.listeners.OnAccountEnabledListener;
import com.xabber.android.data.connection.ConnectionItem;
import com.xabber.android.data.connection.StanzaSender;
import com.xabber.android.data.connection.listeners.OnDisconnectListener;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.NestedMap;
import com.xabber.android.data.entity.UserJid;
import com.xabber.android.data.extension.muc.RoomChat;
import com.xabber.android.data.extension.muc.RoomContact;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.message.AbstractChat;
import com.xabber.android.data.message.ChatContact;
import com.xabber.android.data.message.MessageManager;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.roster.packet.RosterPacket;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.Jid;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Manage contact list (roster).
 *
 * @author alexander.ivanov
 */
public class RosterManager implements OnDisconnectListener, OnAccountEnabledListener,
        OnAccountDisabledListener {

    private static final String LOG_TAG = RosterManager.class.getSimpleName();

    private static RosterManager instance;

    private NestedMap<RosterContact> rosterContacts;

    private final NestedMap<WeakReference<AbstractContact>> contactsCache;

    private RosterManager() {
        rosterContacts = new NestedMap<>();
        contactsCache = new NestedMap<>();
    }

    public static RosterManager getInstance() {
        if (instance == null) {
            instance = new RosterManager();
        }

        return instance;
    }

    @Nullable
    private Roster getRoster(AccountJid account) {
        final AccountItem accountItem = AccountManager.getInstance().getAccount(account);

        if (accountItem == null) {
            return null;
        }

        return Roster.getInstanceFor(accountItem.getConnection());
    }

    @Nullable
    public Presence getPresence(AccountJid account, UserJid user) {
        final Roster roster = getRoster(account);
        if (roster == null) {
            return null;
        } else {
            return roster.getPresence(user.getJid().asBareJid());
        }
    }

    public List<Presence> getPresences(AccountJid account, Jid user) {
        final Roster roster = getRoster(account);
        if (roster == null) {
            return new ArrayList<>();
        } else {
            return roster.getAvailablePresences(user.asBareJid());
        }
    }

    public boolean isSubscribed(AccountJid account, UserJid user) {
        final Roster roster = getRoster(account);
        if (roster == null) {
            return false;
        } else {
            return roster.iAmSubscribedTo(user.getJid());
        }
    }

    public Collection<RosterContact> getAccountRosterContacts(final AccountJid accountJid) {
        return Collections.unmodifiableCollection(rosterContacts.getNested(accountJid.toString()).values());
    }

    public Collection<RosterContact> getAllContacts() {
        return Collections.unmodifiableCollection(rosterContacts.values());
    }

    void onContactsAdded(AccountJid account, Collection<Jid> addresses) {
        final Roster roster = RosterManager.getInstance().getRoster(account);

        Collection<RosterContact> newContacts = new ArrayList<>(addresses.size());


        for (Jid jid : addresses) {
            RosterEntry entry = roster.getEntry(jid.asBareJid());
            try {
                RosterContact contact = convertRosterEntryToRosterContact(account, roster, entry);
                rosterContacts.put(account.toString(),
                        contact.getUser().getBareJid().toString(), contact);
                newContacts.add(contact);
            } catch (UserJid.UserJidCreateException e) {
                LogManager.exception(LOG_TAG, e);
            }
        }

        onContactsChanged(newContacts);
    }

    void onContactsUpdated(AccountJid account, Collection<Jid> addresses) {
        onContactsAdded(account, addresses);
    }

    void onContactsDeleted(AccountJid account, Collection<Jid> addresses) {
        Collection<RosterContact> removedContacts = new ArrayList<>(addresses.size());

        for (Jid jid : addresses) {
            RosterContact contact = rosterContacts.remove(account.toString(), jid.asBareJid().toString());
            if (contact != null) {
                removedContacts.add(contact);
            }
        }

        onContactsChanged(removedContacts);
    }

    @NonNull
    private RosterContact convertRosterEntryToRosterContact(AccountJid account, Roster roster, RosterEntry rosterEntry) throws UserJid.UserJidCreateException {
        final RosterContact contact = RosterContact
                .getRosterContact(account, UserJid.from(rosterEntry.getJid()), rosterEntry.getName());

        final Collection<org.jivesoftware.smack.roster.RosterGroup> groups = roster.getGroups();

        contact.clearGroupReferences();
        for (org.jivesoftware.smack.roster.RosterGroup group : groups) {
            if (group.contains(rosterEntry)) {
                contact.addGroupReference(new RosterGroupReference(new RosterGroup(account, group.getName())));
            }
        }
        contact.setEnabled(true);
        contact.setConnected(true);


        return contact;
    }

    public AbstractContact getAbstractContact(@NonNull AccountJid accountJid, @NonNull UserJid userJid) {
        WeakReference<AbstractContact> contactWeakReference = contactsCache.get(accountJid.toString(), userJid.toString());
        if (contactWeakReference != null && contactWeakReference.get() != null) {
            return contactWeakReference.get();
        }

        AbstractContact newContact = new AbstractContact(accountJid, userJid);
        contactsCache.put(accountJid.toString(), userJid.toString(), new WeakReference<>(newContact));
        return newContact;
    }

    @Nullable
    public RosterContact getRosterContact(AccountJid accountJid, BareJid bareJid) {
        return rosterContacts.get(accountJid.toString(), bareJid.toString());
    }

    @Nullable
    public RosterContact getRosterContact(AccountJid accountJid, UserJid userJid) {
        return getRosterContact(accountJid, userJid.getBareJid());
    }

    /**
     * Gets {@link RoomContact}, {@link RosterContact}, {@link ChatContact} or
     * creates new {@link ChatContact}.
     *
     * @param account
     * @param user
     * @return
     */
    public AbstractContact getBestContact(AccountJid account, UserJid user) {
        AbstractChat abstractChat = MessageManager.getInstance().getChat(account, user);
        if (abstractChat != null && abstractChat instanceof RoomChat) {
            return new RoomContact((RoomChat) abstractChat);
        }
        RosterContact rosterContact = getRosterContact(account, user);
        if (rosterContact != null) {
            return rosterContact;
        }
        if (abstractChat != null) {
            return new ChatContact(abstractChat);
        }
        return new ChatContact(account, user);
    }

    /**
     * @param account
     * @return List of groups in specified account.
     */
    public Collection<String> getGroups(AccountJid account) {
        final Roster roster = getRoster(account);

        Collection<String> returnGroups = new ArrayList<>();

        if (roster == null) {
            return returnGroups;
        }

        final Collection<org.jivesoftware.smack.roster.RosterGroup> groups = roster.getGroups();

        for (org.jivesoftware.smack.roster.RosterGroup rosterGroup : groups) {
            returnGroups.add(rosterGroup.getName());
        }

        return returnGroups;
    }

    /**
     * @return Contact's name.
     */
    public String getName(AccountJid account, UserJid user) {
        RosterContact contact = getRosterContact(account, user);
        if (contact == null) {
            return user.toString();
        }
        return contact.getName();
    }

    /**
     * @return Contact's groups.
     */
    public Collection<String> getGroups(AccountJid account, UserJid user) {
        RosterContact contact = getRosterContact(account, user);
        if (contact == null) {
            return Collections.emptyList();
        }
        return contact.getGroupNames();
    }

    /**
     * Requests to create new contact.
     *
     * @param account
     * @param user
     * @param name
     * @param groups
     * @throws NetworkException
     */
    public void createContact(AccountJid account, UserJid user, String name,
                              Collection<String> groups)
            throws SmackException.NotLoggedInException, XMPPException.XMPPErrorException,
            SmackException.NotConnectedException, InterruptedException, SmackException.NoResponseException {
        final Roster roster = getRoster(account);

        if (roster == null) {
            return;
        }

        if (user.getBareJid() != null) {
            roster.createEntry(user.getBareJid(), name, groups.toArray(new String[groups.size()]));
        }
    }

    /**
     * Requests contact removing.
     *
     */
    public void removeContact(AccountJid account, UserJid user) {

        final Roster roster = getRoster(account);

        if (roster == null) {
            return;
        }

        final RosterEntry entry = roster.getEntry(user.getJid().asBareJid());

        if (entry == null) {
            return;
        }

        Application.getInstance().runInBackgroundUserRequest(new Runnable() {
            @Override
            public void run() {
                try {
                    roster.removeEntry(entry);
                } catch (SmackException.NotLoggedInException | SmackException.NotConnectedException e) {
                    Application.getInstance().onError(R.string.NOT_CONNECTED);
                } catch (SmackException.NoResponseException e) {
                    Application.getInstance().onError(R.string.CONNECTION_FAILED);
                } catch (XMPPException.XMPPErrorException e) {
                    Application.getInstance().onError(R.string.XMPP_EXCEPTION);
                } catch (InterruptedException e) {
                    LogManager.exception(LOG_TAG, e);
                }
            }
        });
    }

    public void setGroups(AccountJid account, UserJid user, Collection<String> groups) throws NetworkException {
        final Roster roster = getRoster(account);

        if (roster == null) {
            return;
        }

        final RosterEntry entry = roster.getEntry(user.getJid().asBareJid());

        if (entry == null) {
            return;
        }

        RosterPacket packet = new RosterPacket();
        packet.setType(IQ.Type.set);
        RosterPacket.Item item = new RosterPacket.Item(user.getBareJid(), entry.getName());
        for (String group : groups) {
            item.addGroupName(group);
        }
        packet.addRosterItem(item);

        StanzaSender.sendStanza(account, packet);
    }

    public void setName(AccountJid account, UserJid user, final String name) {
        final Roster roster = getRoster(account);

        if (roster == null) {
            return;
        }

        final RosterEntry entry = roster.getEntry(user.getJid().asBareJid());

        if (entry == null) {
            return;
        }

        try {
            entry.setName(name.trim());
        } catch (SmackException.NotConnectedException e) {
            Application.getInstance().onError(R.string.NOT_CONNECTED);
        } catch (SmackException.NoResponseException e) {
            Application.getInstance().onError(R.string.CONNECTION_FAILED);
        } catch (XMPPException.XMPPErrorException e) {
            Application.getInstance().onError(R.string.XMPP_EXCEPTION);
        } catch (InterruptedException e) {
            LogManager.exception(LOG_TAG, e);
        }
    }

    /**
     * Requests to remove group from all contacts in account.
     */
    public void removeGroup(AccountJid account, String groupName)
            throws NetworkException {
        final Roster roster = getRoster(account);
        if (roster == null) {
            return;
        }

        final org.jivesoftware.smack.roster.RosterGroup group = roster.getGroup(groupName);
        if (group == null) {
            return;
        }


        Application.getInstance().runInBackgroundUserRequest(new Runnable() {
            @Override
            public void run() {
                for (RosterEntry entry : group.getEntries()) {
                    try {
                        group.removeEntry(entry);
                    } catch (SmackException.NoResponseException e) {
                        Application.getInstance().onError(R.string.CONNECTION_FAILED);
                    } catch (SmackException.NotConnectedException e) {
                        Application.getInstance().onError(R.string.NOT_CONNECTED);
                    } catch (XMPPException.XMPPErrorException e) {
                        Application.getInstance().onError(R.string.XMPP_EXCEPTION);
                    } catch (InterruptedException e) {
                        LogManager.exception(LOG_TAG, e);
                    }
                }

            }
        });
    }

    /**
     * Requests to remove group from all contacts in all accounts.
     *
     */
    public void removeGroup(String group) throws NetworkException {
        for (AccountJid account : AccountManager.getInstance().getEnabledAccounts()) {
            removeGroup(account, group);
        }
    }

    /**
     * Requests to rename group.
     *
     * @param account
     * @param oldGroup can be <code>null</code> for "no group".
     */
    public void renameGroup(AccountJid account, String oldGroup, final String newGroup) {
        if (newGroup.equals(oldGroup)) {
            return;
        }

        final Roster roster = getRoster(account);
        if (roster == null) {
            return;
        }

        if (TextUtils.isEmpty(oldGroup)) {
            Application.getInstance().runInBackgroundUserRequest(new Runnable() {
                @Override
                public void run() {
                    createGroupForUnfiledEntries(newGroup, roster);
                }
            });
            return;
        }

        final org.jivesoftware.smack.roster.RosterGroup group = roster.getGroup(oldGroup);
        if (group == null) {
            return;
        }

        Application.getInstance().runInBackgroundUserRequest(new Runnable() {
            @Override
            public void run() {
                try {
                    group.setName(newGroup);
                } catch (SmackException.NoResponseException e) {
                    Application.getInstance().onError(R.string.CONNECTION_FAILED);
                } catch (SmackException.NotConnectedException e) {
                    Application.getInstance().onError(R.string.NOT_CONNECTED);
                } catch (XMPPException.XMPPErrorException e) {
                    Application.getInstance().onError(R.string.XMPP_EXCEPTION);
                } catch (InterruptedException e) {
                    LogManager.exception(LOG_TAG, e);
                }
            }
        });

    }

    private void createGroupForUnfiledEntries(String newGroup, Roster roster) {
        final Set<RosterEntry> unfiledEntries = roster.getUnfiledEntries();

        final org.jivesoftware.smack.roster.RosterGroup group = roster.createGroup(newGroup);

        try {
            for (RosterEntry entry : unfiledEntries) {
                group.addEntry(entry);
            }
        } catch (SmackException.NoResponseException e) {
            Application.getInstance().onError(R.string.CONNECTION_FAILED);
        } catch (SmackException.NotConnectedException e) {
            Application.getInstance().onError(R.string.NOT_CONNECTED);
        } catch (XMPPException.XMPPErrorException e) {
            Application.getInstance().onError(R.string.XMPP_EXCEPTION);
        } catch (InterruptedException e) {
            LogManager.exception(LOG_TAG, e);
        }
    }

    /**
     * Requests to rename group from all accounts.
     *
     * @param oldGroup can be <code>null</code> for "no group".
     */
    public void renameGroup(String oldGroup, String newGroup) {
        for (AccountJid account : AccountManager.getInstance().getEnabledAccounts()) {
            renameGroup(account, oldGroup, newGroup);
        }
    }

    /**
     * @param account
     * @return Whether roster for specified account has been received.
     */
    public boolean isRosterReceived(AccountJid account) {
        final Roster roster = getRoster(account);
        return roster != null && roster.isLoaded();
    }

    @Override
    public void onDisconnect(ConnectionItem connection) {
        if (!(connection instanceof AccountItem)) {
            return;
        }

        Collection<RosterContact> accountContacts
                = rosterContacts.getNested(connection.getAccount().toString()).values();

        for (RosterContact contact : accountContacts) {
            contact.setConnected(false);
        }
    }

    @Override
    public void onAccountEnabled(AccountItem accountItem) {
        setEnabled(accountItem.getAccount(), true);
    }

    @Override
    public void onAccountDisabled(AccountItem accountItem) {
        setEnabled(accountItem.getAccount(), false);
    }

    /**
     * Sets whether contacts in accounts are enabled.
     */
    private void setEnabled(AccountJid account, boolean enabled) {
        Collection<RosterContact> accountContacts
                = rosterContacts.getNested(account.toString()).values();

        for (RosterContact contact : accountContacts) {
            contact.setEnabled(enabled);
        }
    }

    /**
     * Notifies registered {@link OnContactChangedListener}.
     *
     * @param entities
     */
    public static void onContactsChanged(final Collection<RosterContact> entities) {
        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                for (OnContactChangedListener onContactChangedListener : Application
                        .getInstance().getUIListeners(OnContactChangedListener.class)) {
                    onContactChangedListener.onContactsChanged(entities);
                }
            }
        });
    }

    /**
     * Notifies registered {@link OnContactChangedListener}.
     */
    public static void onContactChanged(AccountJid account, UserJid bareAddress) {
        final Collection<RosterContact> entities = new ArrayList<>();
        RosterContact rosterContact = getInstance().getRosterContact(account, bareAddress);
        if (rosterContact != null) {
            entities.add(rosterContact);
        }
        onContactsChanged(entities);
    }

    /**
     * Notifies registered {@link OnChatStateListener}.
     */
    public static void onChatStateChanged(AccountJid account, UserJid bareAddress) {
        final Collection<RosterContact> entities = new ArrayList<>();
        RosterContact rosterContact = getInstance().getRosterContact(account, bareAddress);
        if (rosterContact != null) {
            entities.add(rosterContact);
        }

        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                for (OnChatStateListener onChatStateListener : Application
                        .getInstance().getUIListeners(OnChatStateListener.class)) {
                    onChatStateListener.onChatStateChanged(entities);
                }
            }
        });
    }
}
