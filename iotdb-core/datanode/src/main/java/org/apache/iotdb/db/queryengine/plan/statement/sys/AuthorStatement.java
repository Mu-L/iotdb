/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.plan.statement.sys;

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.auth.entity.PrivilegeType;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.db.auth.AuthorityChecker;
import org.apache.iotdb.db.queryengine.plan.analyze.QueryType;
import org.apache.iotdb.db.queryengine.plan.statement.AuthorType;
import org.apache.iotdb.db.queryengine.plan.statement.IConfigStatement;
import org.apache.iotdb.db.queryengine.plan.statement.Statement;
import org.apache.iotdb.db.queryengine.plan.statement.StatementType;
import org.apache.iotdb.db.queryengine.plan.statement.StatementVisitor;
import org.apache.iotdb.db.utils.DataNodeAuthUtils;
import org.apache.iotdb.rpc.RpcUtils;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.rpc.TSStatusCode;

import java.util.Collections;
import java.util.List;

public class AuthorStatement extends Statement implements IConfigStatement {

  private final AuthorType authorType;
  private String userName;
  private String roleName;
  private String password;
  private String newPassword;
  private String[] privilegeList;
  private List<PartialPath> nodeNameList;
  private boolean grantOpt;

  /**
   * Constructor with AuthorType.
   *
   * @param type author type
   */
  public AuthorStatement(AuthorType type) {
    super();
    authorType = type;
    switch (authorType) {
      case DROP_ROLE:
        this.setType(StatementType.DELETE_ROLE);
        break;
      case DROP_USER:
        this.setType(StatementType.DELETE_USER);
        break;
      case GRANT_ROLE:
        this.setType(StatementType.GRANT_ROLE_PRIVILEGE);
        break;
      case GRANT_USER:
        this.setType(StatementType.GRANT_USER_PRIVILEGE);
        break;
      case CREATE_ROLE:
        this.setType(StatementType.CREATE_ROLE);
        break;
      case CREATE_USER:
        this.setType(StatementType.CREATE_USER);
        break;
      case REVOKE_ROLE:
        this.setType(StatementType.REVOKE_ROLE_PRIVILEGE);
        break;
      case REVOKE_USER:
        this.setType(StatementType.REVOKE_USER_PRIVILEGE);
        break;
      case UPDATE_USER:
        this.setType(StatementType.MODIFY_PASSWORD);
        break;
      case GRANT_USER_ROLE:
        this.setType(StatementType.GRANT_USER_ROLE);
        break;
      case REVOKE_USER_ROLE:
        this.setType(StatementType.REVOKE_USER_ROLE);
        break;
      case LIST_USER_PRIVILEGE:
        this.setType(StatementType.LIST_USER_PRIVILEGE);
        break;
      case LIST_ROLE_PRIVILEGE:
        this.setType(StatementType.LIST_ROLE_PRIVILEGE);
        break;
      case LIST_USER:
        this.setType(StatementType.LIST_USER);
        break;
      case LIST_ROLE:
        this.setType(StatementType.LIST_ROLE);
        break;
      default:
        throw new IllegalArgumentException("Unknown authorType: " + authorType);
    }
  }

  /**
   * Constructor with OperatorType.
   *
   * @param type statement type
   */
  public AuthorStatement(StatementType type) {
    super();
    authorType = null;
    statementType = type;
  }

  public AuthorType getAuthorType() {
    return authorType;
  }

  public String getUserName() {
    return userName;
  }

  public void setUserName(String userName) {
    this.userName = userName;
  }

  public String getRoleName() {
    return roleName;
  }

  public void setRoleName(String roleName) {
    this.roleName = roleName;
  }

  public String getPassWord() {
    return password;
  }

  public void setPassWord(String password) {
    this.password = password;
  }

  public String getNewPassword() {
    return newPassword;
  }

  public void setNewPassword(String newPassword) {
    this.newPassword = newPassword;
  }

  public String[] getPrivilegeList() {
    return privilegeList;
  }

  public void setPrivilegeList(String[] privilegeList) {
    this.privilegeList = privilegeList;
  }

  public List<PartialPath> getNodeNameList() {
    return nodeNameList != null ? nodeNameList : Collections.emptyList();
  }

  public void setNodeNameList(List<PartialPath> nodeNameList) {
    this.nodeNameList = nodeNameList;
  }

  public boolean getGrantOpt() {
    return grantOpt;
  }

  public void setGrantOpt(boolean grantOpt) {
    this.grantOpt = grantOpt;
  }

  @Override
  public <R, C> R accept(StatementVisitor<R, C> visitor, C context) {
    return visitor.visitAuthor(this, context);
  }

  @Override
  public QueryType getQueryType() {
    QueryType queryType;
    switch (authorType) {
      case CREATE_USER:
      case CREATE_ROLE:
      case DROP_USER:
      case DROP_ROLE:
      case GRANT_ROLE:
      case GRANT_USER:
      case GRANT_USER_ROLE:
      case REVOKE_USER:
      case REVOKE_ROLE:
      case REVOKE_USER_ROLE:
      case UPDATE_USER:
        queryType = QueryType.WRITE;
        break;
      case LIST_USER:
      case LIST_ROLE:
      case LIST_USER_PRIVILEGE:
      case LIST_ROLE_PRIVILEGE:
        queryType = QueryType.READ;
        break;
      default:
        throw new IllegalArgumentException("Unknown authorType: " + authorType);
    }
    return queryType;
  }

  @Override
  public List<PartialPath> getPaths() {
    return nodeNameList != null ? nodeNameList : Collections.emptyList();
  }

  @Override
  public TSStatus checkPermissionBeforeProcess(String userName) {
    switch (authorType) {
      case CREATE_USER:
        if (AuthorityChecker.SUPER_USER.equals(this.userName)) {
          return AuthorityChecker.getTSStatus(
              false, "Cannot create user has same name with admin user");
        }
        if (AuthorityChecker.SUPER_USER.equals(userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }
        return AuthorityChecker.getTSStatus(
            AuthorityChecker.checkSystemPermission(userName, PrivilegeType.MANAGE_USER),
            PrivilegeType.MANAGE_USER);

      case UPDATE_USER:
        // users can change passwords of themselves
        if (AuthorityChecker.SUPER_USER.equals(userName) || this.userName.equals(userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }
        return AuthorityChecker.getTSStatus(
            AuthorityChecker.checkSystemPermission(userName, PrivilegeType.MANAGE_USER),
            PrivilegeType.MANAGE_USER);

      case DROP_USER:
        if (AuthorityChecker.SUPER_USER.equals(this.userName) || this.userName.equals(userName)) {
          return AuthorityChecker.getTSStatus(false, "Cannot drop admin user or yourself");
        }
        if (AuthorityChecker.SUPER_USER.equals(userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }
        return AuthorityChecker.getTSStatus(
            AuthorityChecker.checkSystemPermission(userName, PrivilegeType.MANAGE_USER),
            PrivilegeType.MANAGE_USER);

      case LIST_USER:
        if (AuthorityChecker.SUPER_USER.equals(userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }
        return AuthorityChecker.getTSStatus(
            AuthorityChecker.checkSystemPermission(userName, PrivilegeType.MANAGE_USER),
            PrivilegeType.MANAGE_USER);

      case LIST_USER_PRIVILEGE:
        if (AuthorityChecker.SUPER_USER.equals(userName) || userName.equals(this.userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }
        return AuthorityChecker.getTSStatus(
            AuthorityChecker.checkSystemPermission(userName, PrivilegeType.MANAGE_USER),
            PrivilegeType.MANAGE_USER);

      case LIST_ROLE_PRIVILEGE:
        if (AuthorityChecker.SUPER_USER.equals(userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }
        if (!AuthorityChecker.checkRole(userName, roleName)) {
          return AuthorityChecker.getTSStatus(
              AuthorityChecker.checkSystemPermission(userName, PrivilegeType.MANAGE_ROLE),
              PrivilegeType.MANAGE_ROLE);
        } else {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }

      case LIST_ROLE:
        if (AuthorityChecker.SUPER_USER.equals(userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }
        if (this.userName != null && userName.equals(this.userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        } else {
          return AuthorityChecker.getTSStatus(
              AuthorityChecker.checkSystemPermission(userName, PrivilegeType.MANAGE_ROLE),
              PrivilegeType.MANAGE_ROLE);
        }

      case CREATE_ROLE:
        if (AuthorityChecker.SUPER_USER.equals(this.roleName)) {
          return AuthorityChecker.getTSStatus(
              false, "Cannot create role has same name with admin user");
        }
      case DROP_ROLE:
      case GRANT_USER_ROLE:
      case REVOKE_USER_ROLE:
        if (AuthorityChecker.SUPER_USER.equals(userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }
        return AuthorityChecker.getTSStatus(
            AuthorityChecker.checkSystemPermission(userName, PrivilegeType.MANAGE_ROLE),
            PrivilegeType.MANAGE_ROLE);

      case REVOKE_USER:
      case GRANT_USER:
      case GRANT_ROLE:
      case REVOKE_ROLE:
        if (AuthorityChecker.SUPER_USER.equals(this.userName)) {
          return AuthorityChecker.getTSStatus(
              false, "Cannot grant/revoke privileges of admin user");
        }
        if (AuthorityChecker.SUPER_USER.equals(userName)) {
          return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
        }

        for (String s : privilegeList) {
          PrivilegeType privilegeType = PrivilegeType.valueOf(s.toUpperCase());
          if (privilegeType.isSystemPrivilege()) {
            if (!AuthorityChecker.checkSystemPermissionGrantOption(userName, privilegeType)) {
              return AuthorityChecker.getTSStatus(
                  false,
                  "Has no permission to execute "
                      + authorType
                      + ", please ensure you have these privileges and the grant option is TRUE when granted)");
            }
          } else if (privilegeType.isPathPrivilege()) {
            if (!AuthorityChecker.checkPathPermissionGrantOption(
                userName, privilegeType, nodeNameList)) {
              return AuthorityChecker.getTSStatus(
                  false,
                  "Has no permission to execute "
                      + authorType
                      + ", please ensure you have these privileges and the grant option is TRUE when granted)");
            }
          } else {
            return AuthorityChecker.getTSStatus(
                false, "Not support Relation statement in tree sql_dialect");
          }
        }
        return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
      default:
        throw new IllegalArgumentException("Unknown authorType: " + authorType);
    }
  }

  /**
   * Post-process when the statement is successfully executed.
   *
   * @return null if the post-process succeeds, a status otherwise.
   */
  public TSStatus onSuccess() {
    if (authorType == AuthorType.CREATE_USER) {
      return onCreateUserSuccess();
    } else if (authorType == AuthorType.UPDATE_USER) {
      return onUpdateUserSuccess();
    }
    return null;
  }

  private TSStatus onCreateUserSuccess() {
    TSStatus tsStatus = DataNodeAuthUtils.recordPassword(userName, password, null);
    try {
      RpcUtils.verifySuccess(tsStatus);
    } catch (StatementExecutionException e) {
      return new TSStatus(e.getStatusCode()).setMessage(e.getMessage());
    }
    return null;
  }

  private TSStatus onUpdateUserSuccess() {
    TSStatus tsStatus = DataNodeAuthUtils.recordPassword(userName, newPassword, password);
    try {
      RpcUtils.verifySuccess(tsStatus);
    } catch (StatementExecutionException e) {
      return new TSStatus(e.getStatusCode()).setMessage(e.getMessage());
    }
    return null;
  }
}
