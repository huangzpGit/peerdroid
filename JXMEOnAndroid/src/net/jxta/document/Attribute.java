/*
 * Copyright (c) 2001 Sun Microsystems, Inc.  All rights reserved.
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
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of Project JXTA.  For more
 * information on Project JXTA, please see
 * <http://www.jxta.org/>.
 *
 * This license is based on the BSD license adopted by the Apache Foundation.
 *
 * $Id: Attribute.java,v 1.1 2005/05/02 17:58:37 hamada Exp $
 */

package net.jxta.document;

/**
 * A name value pair which is associated with some base object.
 *
 * @see net.jxta.document.Attributable
 * @see net.jxta.document.StructuredDocument
 * @see net.jxta.document.Element
 *
 **/
public class Attribute {
    
    /**
     *  The object which this attribute extends.
     **/
    private Attributable owner;
    
    /**
     *  Our name
     **/
    private String name;
    
    /**
     *  Our value
     **/
    private String value;
    
    /**
     * Constructor for a new attribute which can be added to an
     * {@link Attributable}.
     *
     * @param name Name for this attribute.
     * @param value Value for this attribute.
     **/
    public Attribute( String name, String value ) {
        this( null, name, value );
    }
    
    /**
     * Constructor for a new attribute which is associated with an
     * @link Attributable} object.
     *
     * @param owner The Atrributable owner of this attribute or null.
     * @param name Name for this attribute.
     * @param value Value for this attribute.
     **/
    public Attribute( Attributable owner, String name, String value ) {
        this.owner = owner;
        this.name = name;
        this.value = value;
    }
    
    /**
     * {@inheritDoc}
     **/
    protected Object clone() {
        return this;    // immutable so we can return self.
    }
    
    /**
     * {@inheritDoc}

     * The two attributes are the same if they have
     * the same owner, name and value.
     *
     * @param target Attribute to be checked with
     * @return boolean if the attributes are equal otherwise false.
     **/
    public boolean equals( Object target ) {
        if (this == target) {
            return true;
        }
        
        if (target instanceof Attribute ) {
            Attribute targAttrib = (Attribute) target;
            
            boolean result = ( (owner.equals(targAttrib.owner)) &&
            name.equals( targAttrib.name ) &&
            value.equals( targAttrib.name ) );
            
            return result;
        }
        else
            return false;
    }
    
    /**
     * {@inheritDoc}
     **/
    public int hashCode() {
        int result = name.hashCode() ^ value.hashCode();
        result ^= (null != owner) ? owner.hashCode() : 0;
        return result;
    }
    
    /**
     * {@inheritDoc}
     **/
    public String toString() {
        return "<" + name + " = \"" + value +"\">";
    }
    
    /**
     *  Return name of this attribute
     *
     *  @return String containing the attribute's name.
     **/
    public String getName() {
        return name;
    }
    
    /**
     *  Return the {@link Attributable} which is the owner of this attribute.
     *
     *  @return Attributable object which owns this attribute.
     **/
    public Attributable getOwner() {
        return owner;
    }
    
    /**
     * Return value of this attribute
     *
     * @return String containing the attribute's value.
     **/
    public String getValue() {
        return value;
    }
}
