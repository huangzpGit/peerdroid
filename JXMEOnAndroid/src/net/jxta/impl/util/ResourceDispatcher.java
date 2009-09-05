/*
 * Copyright (c) 2002 Sun Microsystems, Inc.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *       Sun Microsystems, Inc. for Project JXTA."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Sun", "Sun Microsystems, Inc.", "JXTA" and "Project JXTA" must
 *    not be used to endorse or promote products derived from this
 *    software without prior written permission. For written
 *    permission, please contact Project JXTA at http://www.jxta.org.
 *
 * 5. Products derived from this software may not be called "JXTA",
 *    nor may "JXTA" appear in their name, without prior written
 *    permission of Sun.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL SUN MICROSYSTEMS OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 *====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of Project JXTA.  For more
 * information on Project JXTA, please see
 * <http://www.jxta.org/>.
 *
 * This license is based on the BSD license adopted by the Apache Foundation.
 *
 * $Id: ResourceDispatcher.java,v 1.1 2005/05/11 02:27:10 hamada Exp $
 *
 */

package net.jxta.impl.util;

// This class does not in itself allocate anything; it just does accounting.
// Its role is to make sure that resource consumers ("accounts")
// are garanteed to be able to hold a pre-determined number of items,
// the extra being granted on a first-come-first-serve basis.
// It just replies yes/no to an account that wants to allocate an item.
// Synchronizarion is external.
// Note that this is all essentialy a limitter device. It assumes
// that the resources that are dispatched in that way are not otherwise
// in short supply.
//
// The rules of the game are as follows:
// At initialization, an absolute maximum authorized number of items
// is computed. All item reservations and authorizations are done
// within this budget.
// At any given point in time, out of this maximum, a number of items are
// permanently reserved for the minimum garanteed to each current account,
// a number of items are set aside for future accounts garantee reservation,
// and the rest is open for dynamic attribution on a first come first serve
// basis.
// The current strategy is as follows:
//
// The initialization parameters are:
// - minimum number of garanteed accounts: minAccounts
// - minimum commitement to new accounts up to minAccounts: minReserve
// - maximum commitement to new accounts: maxReserve
// - extra number of dynamically allocatable items: extraItems
//
// We infer the number of items dedicated to reservation: reservedItems
// That is minReserve * minAccounts.
//
// Accounts can ask for a commitment in excess of minReserve. Any reservation
// made by an account beyond the minimum is satified from extraItems
// limitted by what's available and maxReserve. When minAccounts have
// registered, it is possible that reservedItems is exhausted. New accounts
// are then accepted on a best effort basis using extra items exclusively. This
// may cause such new accounts to be given a commitement inferior to minReserve,
// including zero. It is up to the account to reject the offer and give up
// by closing, or to go along with the offer. At this time, we do not try
// to raise the commitement made to an account while it is registered.
//
// During the life of the account, items are allocated first from the set
// reserved by this account. If the account is out of reserved items, an attempt
// is made at getting the item from extraItems.
//
// For each account we count the number of items reserved from reservedItems,
// reserved from extraItems, allocated from the local reserved items
// and allocated from extraItems separately. When an item is released, it is
// accounted to extraItems if the account had anything allocated from
// extra items, or to the local reserved items.
// When an account goes away, the number of items that were reserved from
// reserveItems go back to reserveItems and likewise for those comming
// from extraItems. This is done rather than giving priority to reserve
// items so that the system does not favor reservation beyond its initial
// parameters when an account goes away under load.
//
// When resources are scarce, two modes of operations are available.
// Unfair: each account keeps its items as long it has a use for them. If
// the allocation of a new item is denied for an account, the account just has
// to live with it and try again the next time more items are desired.
// RoundRobin: each account releases each item after one use. When allocation
// of a new item is denied for an account by reason of item shortage, the
// account is placed on a list of eligible accounts. Every time an item is
// released, it is re-assigned to the oldest eligible account.
// From an API point of view the difference is not visible: account users
// are advised to release items after one use. Release returns the account to
// which the item has been re-assigned. If RoundRobin is not used, then
// the item is always re-assigned to the account that releases it unless it
// is not needed, in which case it returns to the available pool.
// So, with round-robin off the following is true:
// a.releaseItem() == (a.needs ? a : null);

import org.apache.log4j.Logger;
import org.apache.log4j.Level;
import net.jxta.util.ResourceAccount;
import java.util.LinkedList;

public class ResourceDispatcher {

    private final static Logger LOG =
	Logger.getLogger(ResourceDispatcher.class.getName());

    private long extraItems;
    private long reservedItems;
    private long maxReservedPerAccount;
    private long minReservedPerAccount;
    private long maxExtraPerAccount;
    private long minExtraPoolSize;
    private int nbEligibles;

    private String myName = null;

    class ClientAccount extends Dlink implements ResourceAccount {

	/**
	 * Tells whether this account has any use for extra resources
	 * or not. This feature is required to support roundRobin mode
	 * properly when resources are scarce.
	 */
	private boolean needs;

	/**
	 * The number of items reserved for this account that may still be
	 * allocated. This decrements when we grant an item allocation. When
	 * it is <= 0, new items may be obtained from extraItems. If obtained
	 * we still decrement. When an item is released, if this counter is
	 * < 0 we return the item to extra items. This counter gets
	 * incremented either way.
	 */
	private long nbReserved; 

	/**
	 * The number out of nbReserved that is due back in reservedItems
	 * when this account disapears.
	 * The rest goes back to extraItems.
	 * NOTE: If we go away with items unaccounted for, we take the option
	 * of accouting them as allocated. In other words, that amount is
	 * not returned to its right full item account. That's why we do not
	 * need to keep track of allocated items. The leak is felt
	 * by this allocator. Alternatively we could pretend that the
	 * leaked resources are not ours; but that migh break the actual
	 * allocator of the resource if it relies on our accounting.
	 */
	private long fromReservedItems;

	/**
	 * Same idea but they have been reserved by reducing the number
	 * of extra items available.
	 */
	private long fromExtraItems;

	/**
	 * The limit for extra items allocation.
	 * When nbReserved is at or below that, extra items cannot be
	 * granted.
	 */
	private long extraLimit;

	/**
	 * The external object for which this account manages items.
	 * This is an opaque cookie to us. Whatever code invokes
	 * releaseItem knows what to do with it and the re-assigned item, but
	 * it needs to be told which of its own object got an item assigned.
	 */
	private Object userObject;

	/**
	 * Creates a client account with this resource manager.
	 * Not for external use.
	 * @param fromReservedItems
	 * @param fromExtraItems
	 * @param extraLimit
	 * @param userObject
	 */
	ClientAccount(long fromReservedItems, long fromExtraItems,
		      long extraLimit, Object userObject) {

	    this.nbReserved = fromReservedItems + fromExtraItems;
	    this.fromReservedItems = fromReservedItems;
	    this.fromExtraItems = fromExtraItems;
	    this.extraLimit = -extraLimit;
	    this.userObject = userObject;
	    this.needs = false;
	}

	/**
	 * Tear down this client account.
	 * Return reserved resources to the main pool.
	 * To accelerate return of resources to the global pool, one
	 * may call close() explicitly. Otherwise it is called by finalize.
	 * Calling close() or letting the account be GC'ed while some of the
	 * resources have not been returned is an error, may create a
	 * leak and may display a warning message.
	 */
	public void close() {
	    notEligible();
	    userObject = null;

	    if ((nbReserved == 0)
		&& (fromReservedItems == 0)
		&& (fromExtraItems == 0)) return;

	    if (nbReserved < (fromReservedItems + fromExtraItems)) {
		// !!! someone just gave up on its resource controler
		// without returning the resources !
		if (LOG.isEnabledFor(Level.WARN)) {
		    LOG.warn("An account was abandonned with resources"
			     + " still allocated.");
		}

		// Release whatever we can.
		if (nbReserved >= fromReservedItems) {
		    releaseExtra(nbReserved - fromReservedItems);
		    releaseReserved(fromReservedItems);
		} else if (nbReserved > 0) {
		    releaseReserved(nbReserved);
		}

	    } else {
		releaseReserved(fromReservedItems);
		releaseExtra(fromExtraItems);
	    }

	    nbReserved = fromReservedItems = fromExtraItems = 0;
	}

	/**
	 * Will close the account.
	 * (close is idempotent).
	 */
	public void finalize() {
	    close();
	}

	/**
	 * Tells if this account is idle (that is, none of the resources
	 * that it controls are currently in use). This means it can be closed
	 * safely.
	 */
	public boolean isIdle() {
	    return (nbReserved == fromExtraItems + fromReservedItems);
	}

	public boolean isEligible() {
	    return isLinked();
	}

	/**
	 * Put that account in the queue of accounts elligible to
	 * receive a resource when one becomes available.
	 */
	public void beEligible() {
	    if ((eligibles != null) && ! isEligible()) newEligible(this);
	}

	/**
	 * Remove that account from the queue of accounts elligible to
	 * receive a resource when one becomes available.
	 */
	public void notEligible() {
	    if ((eligibles != null) && isEligible()) unEligible(this);
	}

	// An extra item is being granted to this account (by being reassigned
	// from another account upon release).
	private void granted() {

	    // In theory, there cannot be an account that should NOT be granted
	    // an item in the eligibles list. For now, check whether the theory
	    // survives observations.
	    // It could happen that the need flag was turned off while this
	    // account was in the eligible list. That's not realy a problem.
	    // Either it will be released immediately, or we could filter
	    // it in mostEligible().
	    if (LOG.isEnabledFor(Level.WARN)) {
		if (nbReserved <= extraLimit) {
		    LOG.warn("An account that should not get an item" +
			     " was found in the eligibles list");
		}
	    }

	    --nbReserved;

	    // We've been assigned an item. No-longer eligible.
	    notEligible();
        }

	/**
	 * Try and grant a certain quantity.
	 *
	 * It is usefull to manage the allocation of variable sized aggregates
	 * when what matters is the cummulated quantity rather than an item
	 * count. Quantity could be a number of bytes needed to store
	 * something for example. The advantage of using this method rather
	 * than obtainItem repeatedly is that it is obvisouly faster if
	 * quantity is more than one or two, and also that it is atomic;
	 * the entire quantity is either granted or denied.
	 * Using this routine is by definition incompatible with the round-robin
	 * mode, which could only re-assign quantities of 1.
	 *
	 * It is legal to use this routine along with round-robin mode if the
	 * same dispatcher is used to manage quantities of 1 in this manner,
	 * but an account that has failed to obtain its desired quantity is
	 * not queued for later re-assignment. And items released with
	 * releaseQuantity() are not re-assigned, so overall it is
	 * probably best to not mix the two.
	 *
	 * @param number The number of units wanted. The unit is harbitrary
	 * It is only meaningfull to the code that uses this dispatcher.
	 * @return boolean whether the requested quantity is authorized.
	 */
	public boolean obtainQuantity(long quantity) {

	    if ((nbReserved - quantity) < extraLimit) {
		// That's asking too much. Denied.
		return false;
	    }

	    if (quantity > nbReserved) {
		// We need to get some or all of it from the extra items.
		long toAsk = nbReserved > 0 ? quantity - nbReserved : quantity;
		long res = holdExtra(toAsk);
		if (res != toAsk) {
		    // Could not get enough. We got nothing.
		    releaseExtra(res);
		    return false;
		}
	    }

	    // Now record it.
	    nbReserved -= quantity;

            if (LOG.isEnabledFor(Level.ERROR)) {
                if (nbReserved > fromReservedItems + fromExtraItems) {
                    LOG.error("Incorrect values after obtaining " + quantity + " : [" + this.toString() + "]");
                }
            }

	    return true;
	}

	/**
	 * Try and grant a new item to this account. If it cannot be done,
	 * the account may be eligible for the next available extra item.
	 * That there is need is assumed (otherwise, why is this called ?).
	 * @return boolean true if an item was granted, false otherwise.
	 */
	public boolean obtainItem() {

	    // Set it for consistency. It will get cleared when
	    // the item is used to satisfy the need.
	    needs = true;

	    if (nbReserved > 0) {
		notEligible();
		--nbReserved;
		return true;  // Its pre-reserved.
	    }

	    // This account may deliberately limit the number of extra
	    // items it uses. this translates into a lower limit for
	    // nbReserved when <= 0.
	    if (nbReserved <= extraLimit) {
		notEligible();
		return false;
	    }

	    if (holdExtra(1) == 1) { // Need authorization.
		notEligible();
		--nbReserved;
		return true;
	    }

	    // We are out of luck but eligible.
	    beEligible();
	    return false;
	}

	/**
	 * This will release a number of items at once rather than
	 * once. To be used in conjunctino with obtainItems(). See that
	 * method.
	 * @param quantity the number of items to be released.
	 */
	public void releaseQuantity(long quantity) {
	    if (nbReserved < 0) {
		releaseExtra(quantity < -nbReserved ? quantity : -nbReserved);
	    }
	    nbReserved += quantity;
            if (LOG.isEnabledFor(Level.ERROR)) {
                if (nbReserved > fromReservedItems + fromExtraItems) {
                    LOG.error("Incorrect values after releasing " + quantity + " : [" + this.toString() + "]");
                }
            }
	}

	/**
	 * This will release an item and return the most eligible account to
	 * re-use this item for. The account that is returned has been granted
	 * the item and thus the invoker is expected to do with this account
	 * whatever an invoker of obtainItem() would do in case of success.
	 * If the items that are managed are threads, the invoker is
	 * likely to be one these threads and it should therefore process
	 * the returned account as it did the one for which it was calling
	 * releaseItem, however be very carefull not to process the new account
	 * in the context of the old one; that would rapidly lead to stack
	 * overflow. In other words, be carefull of not making a construct
	 * equivalent to:
	 * process() {
	 *   doStuff();
	 *   myAccount.releaseItem().getUserObject().process();
	 * }
	 *
	 * That won't work.
	 * Instead do:
	 * work() {
	 *  while (myAccount != null) {
	 *   myAccount.getUserObject().doStuff();
	 *   myAccount = myAccount.releaseItem();
	 *  }
	 * }
	 * Or similar; always go back to base stack level.
	 * It is mandatory to handle accounts returned by releaseItem().
	 * If handling leads to releaseItem, then it has to be done in a
	 * forever loop. No choice. That's typical if the items are threads.
	 * That's normally not happening if the items are memory.
	 * 
	 * If this account is not using any extra
	 * item, it is the only eligible account to reuse this item.
	 * In that case, if this account needs the item, "this" is returned.
	 * Else the item is accounted as released and null is returned.
	 * If this account is using extra items, the item is accounted as
	 * released for this account, and granted to the most eligible account,
	 * which is returned. If roundRobin mode is OFF, the most eligible
	 * account can only be this.
	 * If no account has any need for the item, the item is counted
	 * as released for this item and globaly, and null is returned.
	 * If RoundRobin is ON, this account may not always be eligible
	 * for an extra item.
	 *
	 * @return ResourceAccount the account to which the released item
	 * has been re-assigned. null if the released item was not re-assigned.
	 */

	public ResourceAccount releaseItem() {

	    if (nbReserved < 0) {
		if (eligibles == null) {
		    // RoundRobin is OFF either we reuse it or we let
		    // it go.
		    if (needs) return this;

		    ++nbReserved; 
		    releaseExtra(1);
		    return null;
		}

		// RoundRobin is ON, we compete with others for this item.
		++nbReserved;

		// Update our eligibility which depends on extraLimit and
		// whether we have a use for the item or not.
		if ((nbReserved > extraLimit) && needs) {
		    beEligible();
		}

		ClientAccount next = mostEligible();

		if (next == null) {
		    releaseExtra(1); // noone wants it. return to main pool
		} else {
		    next.granted();
		}
		return next;
	    }

	    // Since we are (back) in our reserved range, we can't be eligible
	    // for extra.
	    notEligible();

	    // In reserved range; we keep using the item if we need it.
	    if (needs) return this;

	    ++nbReserved;
	    return null;
	}

	/**
	 * Call this with true as soon as account needs a new item.
	 * Call this with false as soon as account has all it needs.
	 * Accounts are taken out of the eligible list as soon as one
	 * item is obtained. Calling inNeed() is a way to get the item
	 * back in list (at the end) if the need is still present.
	 * @param needs Whether the account needs a new item or not.
	 */
	public void inNeed(boolean needs) {
	    this.needs = needs;
	    if ((nbReserved < 0) && (nbReserved > extraLimit) && needs) {
		beEligible();
	    } else {
		notEligible();
	    }
	}

	/**
	 * @return Object The userObject that was supplied when creating the
	 * account.
	 */
	public Object getUserObject() {
	    return userObject;
	}

	/**
	 * Set the userObject associated with that account.
	 */
	public void setUserObject(Object obj) {
	    userObject = obj;
	}

	/**
	 * Returns the number of reserved items that can still be obtained by
	 * this account.
	 * If that number is negative it means that all reserved items are
	 * currently in use, and the number is the opposite of the number
	 * of extra items that are also currently in use by that account.
	 * @return long The number of reserved items.
	 */
	public long getNbReserved() {
	    return nbReserved;
	}

        /**
         * Returns some human-readable status and identity information.
         */
        public String toString() {
            return super.toString()+" : needs="+needs+" nbReserved="+nbReserved+" fromReservedItems="+fromReservedItems+" fromExtraItems="+fromExtraItems+" extraLimit="+extraLimit;
        }

    }

    /**
     * The list of eligible accounts.
     */
    private Dlist eligibles;


    /**
     * Construct a Fair Resource Allocator with the given parameters:
     * @param minAccounts The minimum number of client accounts that we want to
     * garantee we can handle. <0 means 0
     * @param minReservedPerAccount The minimum reservation request that we will
     * always grant to accounts as long as we have less than minAccounts <0 means
     * 0.
     * @param maxReservedPerAccount The maximum reservation request that we ever
     * will grant to any given account. <minReservedPerAccount means ==
     * @param extraItems The total number of items that we will authorize
     * beyond what has been reserved. <0 means 0.
     * @param maxExtraPerAccount The maximum number of extra items we will ever
     * let any given account occupy. <0 or >extraItems means ==extraItems.
     * @param minExtraPoolSize The number of extra items that can never be
     * taken out of the extra pool to satisfy a reservation request.
     * @param roundRobin If true, when there is no items available, all
     * eligible accounts are put in a FIFO. Accounts release items often, and the
     * oldest account in the FIFO will get it. If false, accounts always keep
     * items for as long as they can use them, and there is no FIFO of eligible
     * accounts. Accounts can obtain new resources only if available at the time
     * they try to aquire it. RoundRobin is more fair but has more overhead.
     * Neither mode will cause starvation as long as accounts reserve at least
     * one item each. RoundRobin is most usefull when allocating threads.
     */
    public ResourceDispatcher(long minAccounts, long minReservedPerAccount,
			      long maxReservedPerAccount, long extraItems,
			      long maxExtraPerAccount, long minExtraPoolSize,
			      boolean roundRobin,
                              String dispatcherName)
    {
	if (minAccounts < 0) minAccounts = 0;
	if (minReservedPerAccount < 0) minReservedPerAccount = 0;
	if (maxReservedPerAccount < minReservedPerAccount) {
	    maxReservedPerAccount = minReservedPerAccount;
	}
	if (extraItems < 0) extraItems = 0;
        if (minExtraPoolSize < 0) minExtraPoolSize = 0;

	if ((maxExtraPerAccount < 0) || (maxExtraPerAccount > extraItems)) {
	    maxExtraPerAccount = extraItems;
	}

	this.extraItems = extraItems;
	this.minExtraPoolSize = minExtraPoolSize;
	this.maxReservedPerAccount = maxReservedPerAccount;
	this.minReservedPerAccount = minReservedPerAccount;
	this.reservedItems = minAccounts * minReservedPerAccount;
	this.maxExtraPerAccount = maxExtraPerAccount;
	nbEligibles = 0;
	if (roundRobin) eligibles = new Dlist();

        this.myName = dispatcherName;
    }

    private long holdReserved(long req) {
	if (req > reservedItems) req = reservedItems;
	reservedItems -= req;
	return req;
    }

    private void releaseReserved(long nb) {
	reservedItems += nb;
    }

    private long holdExtra(long req) {
	if (req > extraItems) req = extraItems;
	extraItems -= req;
	return req;
    }

    // Get items from the extra pool but only if there is at least
    // minExtraPoolSize item
    // left after that. The goal is to make sure we keep at least one
    // un-reserved item when granting reserved items from the extra pool.
    // Thanks to that, even accounts that could not get a single reserved
    // item still stand a chance to make progress by taking turns using
    // the one extra item left.
    private long holdExtraKeepSome(long req) {
        if (extraItems <= minExtraPoolSize) {
            return 0;
        }
        long allowed = extraItems - minExtraPoolSize;
	if (req > allowed) req = allowed;
	extraItems -= req;
	return req;
    }

    private void releaseExtra(long nb) {
	extraItems += nb;
    }

    private void newEligible(ClientAccount account) {
	++nbEligibles;
	eligibles.putLast(account);
    }

    private ClientAccount mostEligible() {
	if (nbEligibles == 0) return null;
	return (ClientAccount) eligibles.getFirst();
    }

    private void unEligible(ClientAccount account) {
	--nbEligibles;
	account.unlink();
    }

    // Not synch; it's just a snapshot for trace purposes.
    public int getNbEligibles() {
	return nbEligibles;
    }

    /**
     * Creates and returns a new client account.
     * @param nbReq the number of reserved items requested (may not be
     * always granted in full). A negative value is taken to mean 0. 
     * @param maxExtra the number of additional items that this account
     * authorizes to be allocated in addition to the reserved ones. This
     * is typically usefull if the items are threads and if some accounts
     * are not re-entrant. Then nbReq would be 1 and maxExtra would be 0.
     * It is also permitted to have some accounts receive no items at all
     * ever by setting nbReq and maxExtra both to zero. A negative maxExtra
     * is taken as meaning no specified limit, in which case an actual limit
     * may be set silently.
     * @param userObject An opaque cookie that the account object will return
     * when requested. This is usefull to relate an account returned by
     * ClientAccount.releaseItem() to an invoking code relevant object.
     * @return ResourceAccount An account with this allocator.
     */
    public ResourceAccount newAccount(long nbReq, long maxExtra,
				      Object userObject) {

	long extra = 0;    // reserved from extra pool
	long reserved = 0; // reserved from reserved pool

	if (nbReq > maxReservedPerAccount) nbReq = maxReservedPerAccount;

	// Anything beyond the minimum comes from extra items if there's
	// enough.
	if (nbReq > minReservedPerAccount) {
	    extra = holdExtraKeepSome(nbReq - minReservedPerAccount);
	    nbReq = minReservedPerAccount;
	}

	// Then the minimum comes from reserved items, if we can.
	reserved = holdReserved(nbReq);
	nbReq -= reserved;

	// If there's some letf to be had, it means that we're getting
	// short on reserved items, we'll try to compensate by getting
	// more items from extra, but the app should start getting rid
	// of stale accounts if it can.
	if (nbReq > 0) {
	    if (LOG.isEnabledFor(Level.INFO)) {
		LOG.info("Accepting extra account on a best effort basis.");
	    }
	    
	    extra += holdExtraKeepSome(nbReq);
	    if (extra + reserved < minReservedPerAccount) {
		// Even that was not enough to reach our minimal commitment.
		// The app should realy consider some cleanup.
		if (LOG.isEnabledFor(Level.WARN)) {
		    LOG.warn("[" + myName + "] Accepting extra account with below-minimal commitement:[" + userObject + "]");
		}
	    }
	}

	if ((maxExtra > maxExtraPerAccount) || (maxExtra < 0)) {
	    maxExtra = maxExtraPerAccount;
	}

	return new ClientAccount(reserved, extra, maxExtra, userObject);
    }
}
