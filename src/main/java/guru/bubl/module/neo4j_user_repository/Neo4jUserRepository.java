/*
 * Copyright Vincent Blouin under the GPL License version 3
 */

package guru.bubl.module.neo4j_user_repository;

import guru.bubl.module.common_utils.NoEx;
import guru.bubl.module.model.User;
import guru.bubl.module.model.UserUris;
import guru.bubl.module.model.forgot_password.UserForgotPasswordToken;
import guru.bubl.module.neo4j_graph_manipulator.graph.FriendlyResourceNeo4j;
import guru.bubl.module.neo4j_graph_manipulator.graph.search.GraphSearchNeo4j;
import guru.bubl.module.repository.user.ExistingUserException;
import guru.bubl.module.repository.user.NonExistingUserException;
import guru.bubl.module.repository.user.UserRepository;
import org.parboiled.common.StringUtils;

import javax.inject.Inject;
import java.lang.reflect.Field;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static guru.bubl.module.neo4j_graph_manipulator.graph.RestApiUtilsNeo4j.map;

public class Neo4jUserRepository implements UserRepository {

    public static String neo4jType = "user",
            returnQueryPart =
                    "return user.uri, " +
                            "user.email, " +
                            "user." + props.preferredLocales + "," +
                            "user." + props.salt + "," +
                            "user." + props.passwordHash;

    enum props {
        username,
        email,
        preferredLocales,
        creationDate,
        updateTime,
        salt,
        passwordHash,
        forgetPasswordToken,
        changePasswordExpirationDate
    }

    @Inject
    protected Connection connection;

    @Override
    public User createUser(User user) {
        if (emailExists(user.email())) {
            throw new ExistingUserException(
                    user.email()
            );
        }
        if (usernameExists(user.username())) {
            throw new ExistingUserException(
                    user.username()
            );
        }
        String query = "create (user:resource {1})";
        NoEx.wrap(() -> {
            PreparedStatement statement = connection.prepareStatement(query);
            statement.setObject(
                    1,
                    map(
                            FriendlyResourceNeo4j.props.type.name(),
                            neo4jType,
                            FriendlyResourceNeo4j.props.uri.name(),
                            user.id(),
                            props.username.name(),
                            user.username(),
                            props.email.name(),
                            user.email(),
                            props.preferredLocales.name(),
                            user.getPreferredLocalesAsString(),
                            props.creationDate.name(),
                            new Date().getTime(),
                            props.updateTime.name(),
                            new Date().getTime(),
                            props.salt.name(),
                            user.salt(),
                            props.passwordHash.name(),
                            user.passwordHash()
                    )
            );
            return statement.execute();
        }).get();
        return user;
    }

    @Override
    public User findByUsername(String username) throws NonExistingUserException {
        URI uri = new UserUris(username).baseUri();
        String query = String.format(
                "START user=node:node_auto_index('uri:%s') %s",
                uri,
                returnQueryPart
        );
        return NoEx.wrap(() ->
                userFromResult(
                        connection.createStatement().executeQuery(query),
                        username
                )).get();
    }

    @Override
    public User findByEmail(String email) throws NonExistingUserException {
        if (email.trim().equals("")) {
            throw new NonExistingUserException("");
        }
        String query = String.format(
                "START user=node:node_auto_index('email:%s') %s",
                email,
                returnQueryPart
        );
        return NoEx.wrap(() ->
                userFromResult(
                        connection.createStatement().executeQuery(
                                query
                        ),
                        email
                )).get();
    }

    @Override
    public Boolean usernameExists(String username) {
        if (username.trim().equals("")) {
            return false;
        }
        URI uri = new UserUris(username).baseUri();
        String query = String.format(
                "START n=node:node_auto_index('uri:%s') return n.%s",
                uri,
                props.email
        );
        return NoEx.wrap(() ->
                connection.createStatement().executeQuery(query).next()
        ).get();
    }

    @Override
    public Boolean emailExists(String email) {
        if (email.trim().equals("")) {
            return false;
        }
        String query = MessageFormat.format(
                "START n=node:node_auto_index(''email:{0}'') RETURN count(n) as number",
                email
        );
        return NoEx.wrap(() -> {
            ResultSet rs = connection.createStatement().executeQuery(
                    query
            );
            rs.next();
            Integer numberOf = new Integer(
                    rs.getString("number")
            );
            return numberOf != 0;
        }).get();
    }

    @Override
    public void generateForgetPasswordToken(User user, UserForgotPasswordToken userForgotPasswordToken) {
        URI uri = new UserUris(user.username()).baseUri();
        String query = String.format(
                "START user=node:node_auto_index('uri:%s') SET user.%s={1} SET user.%s={2}",
                uri,
                props.forgetPasswordToken,
                props.changePasswordExpirationDate
        );
        NoEx.wrap(() -> {
            PreparedStatement statement = connection.prepareStatement(query);
            statement.setString(
                    1,
                    userForgotPasswordToken.getToken()
            );
            statement.setLong(
                    2,
                    userForgotPasswordToken.getResetPasswordExpirationDate().getTime()
            );
            return statement.execute();
        }).get();
    }

    @Override
    public UserForgotPasswordToken getUserForgetPasswordToken(User user) {
        URI uri = new UserUris(user.username()).baseUri();
        String query = String.format(
                "START user=node:node_auto_index('uri:%s') RETURN user.%s, user.%s",
                uri,
                props.forgetPasswordToken,
                props.changePasswordExpirationDate
        );
        return NoEx.wrap(() -> {
            ResultSet rs = connection.createStatement().executeQuery(
                    query
            );
            rs.next();
            String forgetPasswordToken = rs.getString(
                    "user." + props.forgetPasswordToken
            );
            if (StringUtils.isEmpty(forgetPasswordToken)) {
                return UserForgotPasswordToken.empty();
            }
            Date changePasswordExpirationDate = new Date(
                    rs.getLong(
                            "user." + props.changePasswordExpirationDate
                    )
            );
            return UserForgotPasswordToken.withTokenAndExpirationDate(
                    forgetPasswordToken,
                    changePasswordExpirationDate
            );
        }).get();
    }

    @Override
    public void changePassword(User user) {
        URI uri = new UserUris(user.username()).baseUri();
        String query = String.format(
                "START user=node:node_auto_index('uri:%s') " +
                        "SET user.%s={1}, user.%s={2}, user.%s='', user.%s=''",
                uri,
                props.salt,
                props.passwordHash,
                props.forgetPasswordToken,
                props.changePasswordExpirationDate
        );
        NoEx.wrap(() -> {
            PreparedStatement statement = connection.prepareStatement(query);
            statement.setString(
                    1,
                    user.salt()
            );
            statement.setString(
                    2,
                    user.passwordHash()
            );
            return statement.execute();
        }).get();
    }

    @Override
    public void updatePreferredLocales(User user) {
        String query = String.format(
                "START user=node:node_auto_index('uri:%s') " +
                        "SET user.%s={1}",
                user.id(),
                props.preferredLocales
        );
        NoEx.wrap(() -> {
            PreparedStatement statement = connection.prepareStatement(query);
            statement.setString(
                    1,
                    user.getPreferredLocalesAsString()
            );
            return statement.execute();
        }).get();
    }

    public List<User> searchUsers(String searchTerm, User user) {
        String query = String.format(
                "START user=node:node_auto_index('uri:(%s*) AND type:user') " +
                "RETURN user.uri as uri",
                UserUris.BASE_URI + GraphSearchNeo4j.formatSearchTerm(searchTerm)
        );
        List<User> users = new ArrayList<>();
        return NoEx.wrap(() -> {
            ResultSet rs = connection.createStatement().executeQuery(query);
            while (rs.next()) {
                users.add(
                        User.withUsername(
                                UserUris.ownerUserNameFromUri(
                                        URI.create(rs.getString("uri"))
                                )
                        )
                );
            }
            return users;
        }).get();
    }

    private User userFromResult(ResultSet rs, String identifier) throws SQLException {
        if (!rs.next()) {
            throw new NonExistingUserException(identifier);
        }
        URI userUri = URI.create(
                rs.getString("user.uri")
        );
        User user = User.withEmailAndUsername(
                rs.getString(
                        "user." + props.email
                ),
                UserUris.ownerUserNameFromUri(userUri)
        );
        user.setPreferredLocales(
                rs.getString(
                        "user." + props.preferredLocales
                )
        );
        setSalt(
                user,
                rs.getString(
                        "user." + props.salt
                )
        );
        setPasswordHash(
                user,
                rs.getString(
                        "user." + props.passwordHash
                )
        );
        return user;
    }

    protected void setSalt(User user, String salt) {
        try {
            Field field = User.class.getDeclaredField("salt");
            field.setAccessible(true);
            field.set(user, salt);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    protected void setPasswordHash(User user, String passwordHash) {
        try {
            Field field = User.class.getDeclaredField("passwordHash");
            field.setAccessible(true);
            field.set(user, passwordHash);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }
}
