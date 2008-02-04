/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.enterprise.security.acl;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.*;
import javax.security.auth.Subject;

import com.sun.enterprise.deployment.Group;
import com.sun.enterprise.deployment.Role;
import com.sun.enterprise.deployment.RootDeploymentDescriptor;
import com.sun.enterprise.deployment.PrincipalImpl;
import com.sun.enterprise.deployment.interfaces.SecurityRoleMapper;
import com.sun.enterprise.config.serverbeans.SecurityService;
//import com.sun.enterprise.config.ConfigContext;
import com.sun.enterprise.security.AppservAccessController;
//import com.sun.enterprise.server.ApplicationServer;
import com.sun.enterprise.server.ServerContext;
//import com.sun.enterprise.Switch;
import com.sun.logging.*;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.PostConstruct;


/** This Object maintains  a mapping of users and groups to application
 * specific Roles.
 * Using this object this mapping information could be maintained and
 * queried at a later time. This is a complete rewrite of the previous
 * RoleMapper for JACC related changes.
 * @author Harpreet Singh
 */
@Service
public class RoleMapper implements Serializable, SecurityRoleMapper, PostConstruct {

    @Inject
    private  ServerContext serverContext;
    
    private static Map ROLEMAPPER = new HashMap();
    private static final String DEFAULT_ROLE_NAME = "ANYONE";
    private static Role defaultRole = null;
    private static String defaultRoleName = null;
    private String appName;
    private final Map<String, Subject> roleToSubject =
            new HashMap<String, Subject>();

    // default mapper to emulate Servlet default p2r mapping semantics
    private String defaultP2RMappingClassName = null;
    private DefaultRoleToSubjectMapping defaultRTSM =
            new DefaultRoleToSubjectMapping();
    /* the following 2 Maps are a copy of roleToSubject.
     * This is added as a support for deployment.
     * Should think of optimizing this.
     */
    private final Map<String, Set<Principal>> roleToPrincipal =
            new HashMap<String, Set<Principal>>();
    private final Map<String, Set<Group>> roleToGroup =
            new HashMap<String, Set<Group>>();
    /* The following objects are used to detect conflicts during deployment */
    /* .....Mapping of module (or application) that is presently calling
     * assignRole(). It is set by the startMappingFor() method.
     * After all the subjects have been assigned, stopMappingFor()
     * is called and then the mappings can be checked against
     * those previously assigned.
     */
    private Mapping currentMapping;
    // These override roles mapped in submodules.
    private Set<Role> topLevelRoles;

    // used to identify the application level mapping file
    //TODO:V3 : not sure getModuleId() method ever returns this
    private static final String TOP_LEVEL = "sun-application.xml mapping file";
    // used to log a warning only one time
    private boolean conflictLogged = false;
    // store roles that have a conflict so they are not re-mapped
    private Set<Role> conflictedRoles;
    /* End conflict detection objects */
    private static Logger _logger =
            LogDomains.getLogger(LogDomains.SECURITY_LOGGER);

    private RoleMapper(String appName) {
        this.appName = appName;
        defaultP2RMappingClassName = getDefaultP2RMappingClassName();
    }
   
    private  synchronized void initDefaultRole() {

         if (!isEJBContainer(serverContext)) {
//V3:Commented        Switch sw = Switch.getSwitch();
//V3:Commented        if (sw.getContainerType() == Switch.EJBWEB_CONTAINER) { // 4735725
        
//V3:Commented        }
             return;
         }
         
        if (defaultRole == null) {

            defaultRoleName = DEFAULT_ROLE_NAME;

            try {
                /*V3:Commented                
                ConfigContext configContext =
                ApplicationServer.getServerContext().getConfigContext();
                assert(configContext != null);
                Server configBean =
                ServerBeansFactory.getServerBean(configContext);
                assert(configBean != null);
                SecurityService securityBean =
                ServerBeansFactory.getSecurityServiceBean(configContext);
                assert(securityBean != null);*/
                if (serverContext != null) {
                    SecurityService secService = serverContext.getDefaultHabitat().getComponent(SecurityService.class);
                    assert (secService != null);
                    defaultRoleName = secService.getAnonymousRole();
                }

            } catch (Exception e) {
                _logger.log(Level.WARNING,
                        "java_security.anonymous_role_reading_exception",
                        e);
            }

            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "Default role is: " + defaultRoleName);
            }
            defaultRole = new Role(defaultRoleName);
        }
    }

    /** Returns a RoleMapper corresponding to the AppName.
     * @param appName Application Name of this RoleMapper.
     * @return SecurityRoleMapper for the application
     */
    public static RoleMapper getRoleMapper(String appName) {
        RoleMapper r = (RoleMapper) ROLEMAPPER.get(appName);
        if (r == null) {
            r = new RoleMapper(appName);
            synchronized (RoleMapper.class) {
                ROLEMAPPER.put(appName, r);
            }
        }
        return r;
    }

    /** Set a RoleMapper for the application
     * @param appName Application or module name
     * @param rmap <I>SecurityRoleMapper</I> for the application or the module
     */
    public static void setRoleMapper(String appName, SecurityRoleMapper rmap) {
        synchronized (RoleMapper.class) {
            ROLEMAPPER.put(appName, rmap);
        }
    }

    /**
     * @param appName Application/module name.
     */
    public static void removeRoleMapper(String appName) {

        if (ROLEMAPPER.containsKey(appName)) {
            synchronized (RoleMapper.class) {
                ROLEMAPPER.remove(appName);
            }
        }
    }

    /**
     * @return The application/module name for this RoleMapper
     */
    public String getName() {
        return appName;
    }

    /**
     * @param name The application/module name
     */
    public void setName(String name) {
        this.appName = name;
    }

    /**
     * @param principal A principal that corresponds to the role
     * @param role A role corresponding to this principal
     */
    private void addRoleToPrincipal(final Principal principal, String role) {
        assert roleToSubject != null;
        Subject subject = roleToSubject.get(role);
        final Subject sub = (subject == null) ? new Subject() : subject;
        AppservAccessController.doPrivileged(new PrivilegedAction() {

            public java.lang.Object run() {
                sub.getPrincipals().add(principal);
                return null;
            }
        });
        roleToSubject.put(role, sub);
    }

    /**
     * Remove the given role-principal mapping
     * @param role, Role object
     * @param principal, the principal
     */
    public void unassignPrincipalFromRole(Role role, Principal principal) {
        assert roleToSubject != null;
        String mrole = role.getName();
        final Subject sub = roleToSubject.get(mrole);
        final Principal p = principal;
        if (sub != null) {
            AppservAccessController.doPrivileged(new PrivilegedAction() {

                public java.lang.Object run() {
                    sub.getPrincipals().remove(p);
                    return null;
                }
            });
            roleToSubject.put(mrole, sub);
        }
        if (principal instanceof Group) {
            Set<Group> groups = roleToGroup.get(mrole);
            if (groups != null) {
                groups.remove((Group) principal);
                roleToGroup.put(mrole, groups);
            }
        } else {
            Set<Principal> principals = roleToPrincipal.get(mrole);
            if (principals != null) {
                principals.remove(principal);
                roleToPrincipal.put(mrole, principals);
            }
        }
    }

    // @return true or false depending on activation of
    // the mapping via domain.xml.
    boolean isDefaultRTSMActivated() {
        return (defaultP2RMappingClassName != null);
    }

    /**
     * Returns the RoleToSubjectMapping for the RoleMapping
     * @return Map of role->subject mapping
     */
    public Map getRoleToSubjectMapping() {
        // this causes the last currentMapping information to be added
        checkAndAddMappings();
        assert roleToSubject != null;
        if (roleToSubject.isEmpty() && isDefaultRTSMActivated()) {
            return defaultRTSM;
        }
        return roleToSubject;
    }

    // The method that does the work for assignRole().
    private void internalAssignRole(Principal p, Role r) {
        String role = r.getName();
        if (_logger.isLoggable(Level.FINE)) {
            _logger.log(Level.FINE, "SECURITY:RoleMapper Assigning Role " + role +
                    " to  " + p.getName());
        }
        addRoleToPrincipal(p, role);
        if (p instanceof Group) {
            Set<Group> groups = roleToGroup.get(role);
            if (groups == null) {
                groups = new HashSet<Group>();
            }
            groups.add((Group) p);
            roleToGroup.put(role, groups);
        } else {
            Set<Principal> principals = roleToPrincipal.get(role);
            if (principals == null) {
                principals = new HashSet<Principal>();
            }
            principals.add(p);
            roleToPrincipal.put(role, principals);
        }
    }

    /**
     * Assigns a Principal to the specified role. This method delegates
     * work to internalAssignRole() after checking for conflicts.
     * RootDeploymentDescriptor added as a fix for:
     * https://glassfish.dev.java.net/issues/show_bug.cgi?id=2475
     *
     * The first time this is called, a new Mapping object is created
     * to store the role mapping information. When called again from
     * a different module, the old mapping information is checked and
     * stored and a new Mapping object is created.
     *
     * @param p The principal that needs to be assigned to the role.
     * @param r The Role the principal is being assigned to.
     * @param rdd The descriptor of the module containing the role mapping
     */
    public void assignRole(Principal p, Role r, RootDeploymentDescriptor rdd) {
        assert rdd != null;
        String callingModuleID = getModuleID(rdd);

        if (currentMapping == null) {
            currentMapping = new Mapping(callingModuleID);
        } else if (!callingModuleID.equals(currentMapping.owner)) {
            checkAndAddMappings();
            currentMapping = new Mapping(callingModuleID);
        }

        // when using the top level mapping
        if (callingModuleID.equals(TOP_LEVEL) &&
                topLevelRoles == null) {
            topLevelRoles = new HashSet<Role>();
        }

        // store principal and role temporarily until stopMappingFor called
        currentMapping.addMapping(p, r);
    }

    /**
     * Returns an enumeration of roles for this rolemapper.
     */
    public Iterator getRoles() {
        assert roleToSubject != null;
        return roleToSubject.keySet().iterator(); // All the roles
    }

    /**
     * Returns an enumeration of Groups assigned to the given role
     * @param The Role to which the groups are assigned to.
     */
    public Enumeration getGroupsAssignedTo(Role r) {
        assert roleToGroup != null;
        Set<Group> s = roleToGroup.get(r.getName());
        if (s == null) {
            return Collections.enumeration(Collections.EMPTY_SET);
        }
        return Collections.enumeration(s);
    }

    /**
     * Returns an enumeration of Principals assigned to the given role
     * @param The Role to which the principals are assigned to.
     */
    public Enumeration getUsersAssignedTo(Role r) {
        assert roleToPrincipal != null;
        Set<Principal> s = roleToPrincipal.get(r.getName());
        if (s == null) {
            return Collections.enumeration(Collections.EMPTY_SET);
        }
        return Collections.enumeration(s);
    }

    public void unassignRole(Role r) {
        if (r != null) {
            String role = r.getName();
            roleToSubject.remove(role);
            roleToPrincipal.remove(role);
            roleToGroup.remove(role);
        }
    }

    /**
     * @return String. String representation of the RoleToPrincipal Mapping
     */
    public String toString() {

        StringBuffer s = new StringBuffer("RoleMapper:");
        for (Iterator e = this.getRoles(); e.hasNext();) {
            String r = (String) e.next();
            s.append("\n\tRole (" + r + ") has Principals(");
            Subject sub = roleToSubject.get(r);
            Iterator it = sub.getPrincipals().iterator();
            for (; it.hasNext();) {
                Principal p = (Principal) it.next();
                s.append(p.getName() + " ");
            }
            s.append(")");
        }
        if (_logger.isLoggable(Level.FINER)) {
            _logger.log(Level.FINER, s.toString());
        }
        return s.toString();
    }

    /** Copy constructor. This is called from the JSR88 implementation.
     * This is not stored into the internal rolemapper maps.
     */
    public RoleMapper(RoleMapper r) {
        this.appName = r.getName();
        for (Iterator it = r.getRoles(); it.hasNext();) {
            String role = (String) it.next();
            // recover groups
            Enumeration groups = r.getGroupsAssignedTo(new Role(role));
            Set<Group> groupsToRole = new HashSet<Group>();
            for (; groups.hasMoreElements();) {
                Group gp = (Group) groups.nextElement();
                groupsToRole.add(new Group(gp.getName()));
                addRoleToPrincipal(gp, role);
            }
            this.roleToGroup.put(role, groupsToRole);

            // recover principles
            Enumeration users = r.getUsersAssignedTo(new Role(role));
            Set<Principal> usersToRole = new HashSet<Principal>();
            for (; users.hasMoreElements();) {
                PrincipalImpl gp = (PrincipalImpl) users.nextElement();
                usersToRole.add(new PrincipalImpl(gp.getName()));
                addRoleToPrincipal(gp, role);
            }
            this.roleToPrincipal.put(role, usersToRole);
        }
    }

    /**
     * @returns the class name used for default Principal to role mapping
     *          return null if default P2R mapping is not supported.
     */
     private String getDefaultP2RMappingClassName() {
        String className = null;
        try {
//V3:Commented            ServerContext serverContext = ApplicationServer.getServerContext();
            if (serverContext != null) {
//V3:Commented                ConfigContext configContext = serverContext.getConfigContext();
                SecurityService securityService = serverContext.getDefaultHabitat().getComponent(SecurityService.class);

//V3:Commented                if (configContext != null) {
//V3:Commented                    SecurityService securityService =
//V3:Commented                        ServerBeansFactory.getSecurityServiceBean(configContext);
                if (securityService != null && Boolean.parseBoolean(securityService.getActivateDefaultPrincipalToRoleMapping())) {
//V3:Commented                       securityService.isActivateDefaultPrincipalToRoleMapping()==true) {
                    className = securityService.getMappedPrincipalClass();
                    if (className == null || "".equals(className)) {
                        className = "com.sun.enterprise.deployment.Group";
                    }
                }
            }
            if (className == null) {
                return null;
            }
            Class clazz = Class.forName(className);
            Class[] argClasses = new Class[]{String.class};
            Object[] arg = new Object[]{"anystring"};
            Constructor c = clazz.getConstructor(argClasses);
            Principal principal = (Principal) c.newInstance(arg);
            //verify that this class is a Principal class and has a constructor(string)
            return className;
        } catch (Exception e) {
            _logger.log(Level.SEVERE, "pc.getDefaultP2RMappingClass: " + e);
            return null;
        }
    }

    /*
     * Only web/ejb BundleDescriptor and Application descriptor objects
     * are used for role mapping currently. If other subtypes of
     * RootDeploymentDescriptor are used in the future, they should
     * be added here.
     */
    private String getModuleID(RootDeploymentDescriptor rdd) {
        //V3: Can we use this : return  rdd.getModuleID();
    /*V3:Comment
        if (rdd instanceof Application) {
        return TOP_LEVEL;
        } else if (rdd instanceof BundleDescriptor) {
        return ((BundleDescriptor) rdd).getModuleDescriptor().getArchiveUri();
        } else {
        // cannot happen unless glassfish code is changed
        throw new AssertionError(rdd.getClass() +
        " is not a known descriptor type");
        }*/
        if (rdd.isApplication()) {
            return TOP_LEVEL;
        } else if (rdd.getModuleDescriptor() != null) {
            return rdd.getModuleDescriptor().getArchiveUri();
        } else {
            // cannot happen unless glassfish code is changed
            throw new AssertionError(rdd.getClass() +
                    " is not a known descriptor type");
        }

    }

    /*
     * For each role in the current mapping:
     *
     * First check that the role does not already exist in the
     * top-level mapping. If it does, then the top-level role mapping
     * overrides the current one and we do not need to check if they
     * conflict. Just continue with the next role.
     *
     * If the current mapping is from the top-level file, then
     * check to see if the role has already been mapped. If so,
     * do not need to check for conflicts. Simply override and
     * assign the role.
     * 
     * If the above cases do not apply, check for conflicts
     * with roles already set. If there is a conflict, it is
     * between two submodules, so the role should be unmapped
     * in the existing role mappings.
     *
     */
    private void checkAndAddMappings() {
        if (currentMapping == null) {
            return;
        }

        for (Role r : currentMapping.getRoles()) {

            if (topLevelRoles != null && topLevelRoles.contains(r)) {
                logConflictWarning();
                if (_logger.isLoggable(Level.FINE)) {
                    _logger.log(Level.FINE, "Role " + r +
                            " from module " + currentMapping.owner +
                            " is being overridden by top-level mapping.");
                }

                continue;
            }

            if (currentMapping.owner.equals(TOP_LEVEL)) {
                topLevelRoles.add(r);
                if (roleToSubject.keySet().contains(r.getName())) {
                    logConflictWarning();
                    if (_logger.isLoggable(Level.FINE)) {
                        _logger.log(Level.FINE, "Role " + r +
                                " from top-level mapping descriptor is " +
                                "overriding existing role in sub module.");
                    }

                    unassignRole(r);
                }

            } else if (roleConflicts(r, currentMapping.getPrincipals(r))) {
                // detail message already logged
                logConflictWarning();
                unassignRole(r);
                continue;
            }

            // no problems, so assign role
            for (Principal p : currentMapping.getPrincipals(r)) {
                internalAssignRole(p, r);
            }

        }

        // clear current mapping
        currentMapping = null;
    }

    private boolean isEJBContainer(ServerContext serverContext) {
        return false;
    }

    private boolean roleConflicts(Role r, Set<Principal> ps) {

        // check to see if there has been a previous conflict
        if (conflictedRoles != null && conflictedRoles.contains(r)) {
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE,
                        "Role " + r + " from module " + currentMapping.owner +
                        " has already had a conflict with other modules.");
            }

            return true;
        }

// if role not previously mapped, no conflict
        if (!roleToSubject.keySet().contains(r.getName())) {
            return false;
        }

// check number of mappings first
        int targetNumPrin = ps.size();
        int actualNum = 0;
        Set<Principal> pSet = roleToPrincipal.get(r.getName());
        Set<Group> gSet = roleToGroup.get(r.getName());
        actualNum +=
                (pSet == null) ? 0 : pSet.size();
        actualNum +=
                (gSet == null) ? 0 : gSet.size();
        if (targetNumPrin != actualNum) {
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE,
                        "Module " + currentMapping.owner +
                        " has different number of mappings for role " +
                        r.getName() +
                        " than other mapping files");
            }

            if (conflictedRoles == null) {
                conflictedRoles = new HashSet<Role>();
            }

            conflictedRoles.add(r);
            return true;
        }

// check the principals and groups
        boolean fail = false;
        for (Principal p : ps) {
            if (p instanceof Group) {
                if (gSet != null && !gSet.contains((Group) p)) {
                    fail = true;
                }

            } else if (pSet != null && !pSet.contains(p)) {
                fail = true;
            }

            if (fail) {
                if (_logger.isLoggable(Level.FINE)) {
                    _logger.log(Level.FINE,
                            "Role " + r + " in module " + currentMapping.owner +
                            " is not included in other modules.");
                }

                if (conflictedRoles == null) {
                    conflictedRoles = new HashSet<Role>();
                }

                conflictedRoles.add(r);
                return true;
            }

        }

        // no conflicts
        return false;
    }

    private void logConflictWarning() {
        if (!conflictLogged) {
            _logger.log(Level.WARNING, "java_security.role_mapping_conflict",
                    getName());
            conflictLogged =
                    true;
        }

    }

    /*
     * Used to represent the role mapping of a single
     * descriptor file.
     */
    private static class Mapping implements Serializable {

        private final String owner;
        private final Map<Role, Set<Principal>> roleMap;

        Mapping(String owner) {
            this.owner = owner;
            roleMap = new HashMap<Role, Set<Principal>>();
        }

        void addMapping(Principal p, Role r) {
            Set<Principal> pSet = roleMap.get(r);
            if (pSet == null) {
                pSet = new HashSet<Principal>();
                roleMap.put(r, pSet);
            }
            pSet.add(p);
        }

        Set<Role> getRoles() {
            return roleMap.keySet();
        }

        Set<Principal> getPrincipals(Role r) {
            return roleMap.get(r);
        }
    }

    class DefaultRoleToSubjectMapping extends HashMap {

        private HashMap roleMap = new HashMap();

        DefaultRoleToSubjectMapping() {
            super();
        }

        Principal getSameNamedPrincipal(String roleName) {
            try {
                Class clazz = Class.forName(defaultP2RMappingClassName);
                Class[] argClasses = new Class[]{String.class};
                Object[] arg = new Object[]{new String(roleName)};
                Constructor c = clazz.getConstructor(argClasses);
                Principal principal = (Principal) c.newInstance(arg);
                return principal;
            } catch (Exception e) {
                _logger.log(Level.SEVERE, "rm.getSameNamedPrincipal", new Object[]{roleName, e});
                throw new RuntimeException("Unable to get principal by default p2r mapping");
            }
        }

        public Object get(Object key) {
            assert key instanceof String;
            synchronized (roleMap) {
                Subject s = (Subject) roleMap.get(key);
                if (s == null && key instanceof String) {
                    final Subject fs = new Subject();
                    final String roleName = (String) key;
                    AppservAccessController.doPrivileged(new PrivilegedAction() {

                        public java.lang.Object run() {
                            fs.getPrincipals().add(getSameNamedPrincipal(roleName));
                            return null;
                        }
                    });
                    roleMap.put(key, fs);
                    s = fs;
                }
                return s;
            }
        }
    }

    public void postConstruct() {
       initDefaultRole();
    }

}
