package com.tabeldata.oauth.repository;

import com.maryanto.dimas.plugins.web.commons.ui.datatables.DataTablesRequest;
import com.maryanto.dimas.plugins.web.commons.ui.datatables.dao.DaoDataTablesPattern;
import com.tabeldata.oauth.models.OauthAccessTokenExtended;
import com.tabeldata.oauth.models.OauthAccessTokenHistory;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.support.SqlLobValue;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2RefreshToken;
import org.springframework.security.oauth2.common.util.SerializationUtils;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.AuthenticationKeyGenerator;
import org.springframework.security.oauth2.provider.token.DefaultAuthenticationKeyGenerator;
import org.springframework.security.oauth2.provider.token.store.JdbcTokenStore;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class JdbcTokenStoreCustomizer extends JdbcTokenStore implements Serializable, DaoDataTablesPattern<OauthAccessTokenExtended> {

    private final static Logger console = LoggerFactory.getLogger(JdbcTokenStoreCustomizer.class);

    private String insertAccessTokenSql = "insert into oauth.access_token (token_id, token, auth_id, user_name, client_id, authentication, refresh_token, ip_address)\n" +
            "values (?, ?, ?, ?, ?, ?, ?, ?)";
    private String selectAccessTokenSql = "select token_id, token\n" +
            "from oauth.access_token\n" +
            "where token_id = ?";
    private String selectAccessTokenAuthenticationSql = "select token_id, authentication\n" +
            "from oauth.access_token\n" +
            "where token_id = ?";
    private String selectAccessTokenFromAuthenticationSql = "select token_id, token\n" +
            "from oauth.access_token\n" +
            "where auth_id = ?";
    private String selectAccessTokensFromUserNameAndClientIdSql = "select token_id, token\n" +
            "from oauth.access_token\n" +
            "where user_name = ?\n" +
            "  and client_id = ?";
    private String selectAccessTokensFromUserNameSql = "select token_id, token\n" +
            "from oauth.access_token\n" +
            "where user_name = ?";
    private String selectAccessTokensFromClientIdSql = "select token_id, token\n" +
            "from oauth.access_token\n" +
            "where client_id = ?";
    private String deleteAccessTokenSql = "delete\n" +
            "from oauth.access_token\n" +
            "where token_id = ?";
    private String insertRefreshTokenSql = "insert into oauth.refresh_token (token_id, token, authentication)\n" +
            "values (?, ?, ?)";
    private String selectRefreshTokenSql = "select token_id, token\n" +
            "from oauth.refresh_token\n" +
            "where token_id = ?";
    private String selectRefreshTokenAuthenticationSql = "select token_id, authentication\n" +
            "from oauth.refresh_token\n" +
            "where token_id = ?";
    private String deleteRefreshTokenSql = "delete\n" +
            "from oauth.refresh_token\n" +
            "where token_id = ?";
    private String deleteAccessTokenFromRefreshTokenSql = "delete\n" +
            "from oauth.access_token\n" +
            "where refresh_token = ?";
    private String insertHistoryAccessTokenSql = "insert into oauth.history_access_token (id, access_id, client_id, token, ip_address, user_name, login_at, is_logout, logout_at, logout_by)\n" +
            "VALUES (uuid_generate_v4(), ?, ?, ?, ?, ?, now(), false, null, null)";
    private String updateHistoryAccessTokenSql = "update oauth.history_access_token\n" +
            "set is_logout = true,\n" +
            "    logout_at = now(),\n" +
            "    logout_by = ?\n" +
            "where access_id = ?\n" +
            "  and is_logout = false";

    private AuthenticationKeyGenerator authenticationKeyGenerator = new DefaultAuthenticationKeyGenerator();
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    private String getRemoteAddress() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        WebAuthenticationDetails details = (WebAuthenticationDetails) auth.getDetails();
        String ipAddress = details.getRemoteAddress();
        return ipAddress;
    }


    public JdbcTokenStoreCustomizer(DataSource dataSource) {
        super(dataSource);
        Assert.notNull(dataSource, "DataSource required");
        setAuthenticationKeyGenerator(authenticationKeyGenerator);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public OAuth2AccessToken getAccessToken(OAuth2Authentication authentication) {
        OAuth2AccessToken accessToken = null;
        String key = this.authenticationKeyGenerator.extractKey(authentication);

        try {
            accessToken = this.jdbcTemplate.queryForObject(
                    this.selectAccessTokenFromAuthenticationSql,
                    (rs, rowNum) -> JdbcTokenStoreCustomizer.this.deserializeAccessToken(rs.getBytes(2)),
                    key);
        } catch (EmptyResultDataAccessException var5) {
            if (console.isDebugEnabled()) {
                console.debug("Failed to find access token for authentication " + authentication);
            }
        } catch (IllegalArgumentException var6) {
            console.error("Could not extract access token for authentication " + authentication, var6);
        }

        if (accessToken != null && !key.equals(this.authenticationKeyGenerator.extractKey(this.readAuthentication(accessToken.getValue())))) {
            this.removeAccessToken(accessToken.getValue());
            this.storeAccessToken(accessToken, authentication);
        }

        return accessToken;
    }

    @Override
    public void storeAccessToken(OAuth2AccessToken token, OAuth2Authentication authentication) {
        String refreshToken = null;
        if (token.getRefreshToken() != null) {
            refreshToken = token.getRefreshToken().getValue();
        }

        if (this.readAccessToken(token.getValue()) != null) {
            this.removeAccessToken(token.getValue());
        }

        String authId = this.authenticationKeyGenerator.extractKey(authentication);
        String tokenId = this.extractTokenKey(token.getValue());
        String username = authentication.isClientOnly() ? null : authentication.getName();
        String clientId = authentication.getOAuth2Request().getClientId();

        SqlLobValue tokenLobValue = new SqlLobValue(this.serializeAccessToken(token));
        this.jdbcTemplate.update(
                this.insertAccessTokenSql,
                new Object[]{
                        tokenId,
                        tokenLobValue,
                        authId,
                        username,
                        clientId,
                        new SqlLobValue(this.serializeAuthentication(authentication)),
                        this.extractTokenKey(refreshToken),
                        getRemoteAddress()
                }, new int[]{Types.VARCHAR, Types.BLOB, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.BLOB, Types.VARCHAR, Types.VARCHAR});

        this.jdbcTemplate.update(
                this.insertHistoryAccessTokenSql,
                new Object[]{
                        tokenId,
                        clientId,
                        tokenLobValue,
                        getRemoteAddress(),
                        username},
                new int[]{Types.VARCHAR, Types.VARCHAR, Types.BLOB, Types.VARCHAR, Types.VARCHAR});
    }

    @Override
    public OAuth2AccessToken readAccessToken(String tokenValue) {
        OAuth2AccessToken accessToken = null;

        try {
            accessToken = this.jdbcTemplate.queryForObject(
                    this.selectAccessTokenSql,
                    (rs, rowNum) -> JdbcTokenStoreCustomizer.this.deserializeAccessToken(rs.getBytes(2)),
                    this.extractTokenKey(tokenValue));
        } catch (EmptyResultDataAccessException var4) {
            if (console.isInfoEnabled()) {
                console.info("Failed to find access token for token " + tokenValue);
            }
        } catch (IllegalArgumentException var5) {
            console.warn("Failed to deserialize access token for " + tokenValue, var5);
            this.removeAccessToken(tokenValue);
        }

        return accessToken;
    }

    @Override
    public void removeAccessToken(OAuth2AccessToken token) {
        this.removeAccessToken(token.getValue());
    }

    public void removeAccessToken(OAuth2AccessToken token, String username) {
        this.removeAccessToken(token.getValue(), username);
    }

    @Override
    public void removeAccessToken(String tokenValue) {
        String tokenId = this.extractTokenKey(tokenValue);

        this.jdbcTemplate.update(
                this.deleteAccessTokenSql,
                tokenId
        );

        this.jdbcTemplate.update(
                this.updateHistoryAccessTokenSql,
                "timeout", tokenId);
    }

    public void removeAccessToken(String tokenValue, String username) {
        String tokenId = this.extractTokenKey(tokenValue);

        this.jdbcTemplate.update(
                this.deleteAccessTokenSql,
                tokenId
        );

        this.jdbcTemplate.update(
                this.updateHistoryAccessTokenSql,
                username, tokenId);
    }

    @Override
    public OAuth2Authentication readAuthentication(OAuth2AccessToken token) {
        return this.readAuthentication(token.getValue());
    }

    @Override
    public OAuth2Authentication readAuthentication(String token) {
        OAuth2Authentication authentication = null;

        try {
            authentication = this.jdbcTemplate.queryForObject(
                    this.selectAccessTokenAuthenticationSql,
                    (rs, rowNum) -> JdbcTokenStoreCustomizer.this.deserializeAuthentication(rs.getBytes(2)),
                    this.extractTokenKey(token));
        } catch (EmptyResultDataAccessException var4) {
            if (console.isInfoEnabled()) {
                console.info("Failed to find access token for token " + token);
            }
        } catch (IllegalArgumentException var5) {
            console.warn("Failed to deserialize authentication for " + token, var5);
            this.removeAccessToken(token);
        }

        return authentication;
    }

    @Override
    public void storeRefreshToken(OAuth2RefreshToken refreshToken, OAuth2Authentication authentication) {
        this.jdbcTemplate.update(
                this.insertRefreshTokenSql,
                new Object[]{
                        this.extractTokenKey(refreshToken.getValue()),
                        new SqlLobValue(this.serializeRefreshToken(refreshToken)),
                        new SqlLobValue(this.serializeAuthentication(authentication))},
                new int[]{12, 2004, 2004});
    }

    @Override
    public OAuth2RefreshToken readRefreshToken(String token) {
        OAuth2RefreshToken refreshToken = null;

        try {
            refreshToken = this.jdbcTemplate.queryForObject(
                    this.selectRefreshTokenSql,
                    (rs, rowNum) -> JdbcTokenStoreCustomizer.this.deserializeRefreshToken(rs.getBytes(2)),
                    this.extractTokenKey(token));
        } catch (EmptyResultDataAccessException var4) {
            if (console.isInfoEnabled()) {
                console.info("Failed to find refresh token for token " + token);
            }
        } catch (IllegalArgumentException var5) {
            console.warn("Failed to deserialize refresh token for token " + token, var5);
            this.removeRefreshToken(token);
        }

        return refreshToken;
    }

    @Override
    public void removeRefreshToken(OAuth2RefreshToken token) {
        this.removeRefreshToken(token.getValue());
    }

    @Override
    public void removeRefreshToken(String token) {
        this.jdbcTemplate.update(this.deleteRefreshTokenSql, this.extractTokenKey(token));
    }

    @Override
    public OAuth2Authentication readAuthenticationForRefreshToken(OAuth2RefreshToken token) {
        return this.readAuthenticationForRefreshToken(token.getValue());
    }

    @Override
    public OAuth2Authentication readAuthenticationForRefreshToken(String value) {
        OAuth2Authentication authentication = null;

        try {
            authentication = this.jdbcTemplate.queryForObject(
                    this.selectRefreshTokenAuthenticationSql,
                    (rs, rowNum) -> JdbcTokenStoreCustomizer.this.deserializeAuthentication(rs.getBytes(2)),
                    this.extractTokenKey(value));
        } catch (EmptyResultDataAccessException var4) {
            if (console.isInfoEnabled()) {
                console.info("Failed to find access token for token " + value);
            }
        } catch (IllegalArgumentException var5) {
            console.warn("Failed to deserialize access token for " + value, var5);
            this.removeRefreshToken(value);
        }

        return authentication;
    }

    @Override
    public void removeAccessTokenUsingRefreshToken(OAuth2RefreshToken refreshToken) {
        this.removeAccessTokenUsingRefreshToken(refreshToken.getValue());
    }

    @Override
    public void removeAccessTokenUsingRefreshToken(String refreshToken) {
        this.jdbcTemplate.update(this.deleteAccessTokenFromRefreshTokenSql, new Object[]{this.extractTokenKey(refreshToken)}, new int[]{12});
    }

    public Collection<OAuth2AccessToken> findTokensByClientId(String clientId) {
        List accessTokens = new ArrayList();

        try {
            accessTokens = this.jdbcTemplate.query(
                    this.selectAccessTokensFromClientIdSql,
                    new JdbcTokenStoreCustomizer.SafeAccessTokenRowMapper(),
                    clientId);
        } catch (EmptyResultDataAccessException var4) {
            if (console.isInfoEnabled()) {
                console.info("Failed to find access token for clientId " + clientId);
            }
        }

        accessTokens = this.removeNulls(accessTokens);
        return accessTokens;
    }

    public Collection<OAuth2AccessToken> findTokensByUserName(String userName) {
        List<OAuth2AccessToken> accessTokens = new ArrayList<>();

        try {
            accessTokens = this.jdbcTemplate.query(
                    this.selectAccessTokensFromUserNameSql,
                    new JdbcTokenStoreCustomizer.SafeAccessTokenRowMapper(),
                    userName);
        } catch (EmptyResultDataAccessException var4) {
            if (console.isInfoEnabled()) {
                console.info("Failed to find access token for userName " + userName);
            }
        }

        accessTokens = this.removeNulls(accessTokens);
        return accessTokens;
    }

    public Collection<OAuth2AccessToken> findTokensByClientIdAndUserName(String clientId, String userName) {
        List<OAuth2AccessToken> accessTokens = new ArrayList<>();

        try {
            accessTokens = this.jdbcTemplate.query(
                    this.selectAccessTokensFromUserNameAndClientIdSql,
                    new JdbcTokenStoreCustomizer.SafeAccessTokenRowMapper(),
                    userName, clientId);
        } catch (EmptyResultDataAccessException var5) {
            if (console.isInfoEnabled()) {
                console.info("Failed to find access token for clientId " + clientId + " and userName " + userName);
            }
        }

        accessTokens = this.removeNulls(accessTokens);
        return accessTokens;
    }

    private List<OAuth2AccessToken> removeNulls(List<OAuth2AccessToken> accessTokens) {
        List<OAuth2AccessToken> tokens = new ArrayList<>();
        Iterator var3 = accessTokens.iterator();

        while (var3.hasNext()) {
            OAuth2AccessToken token = (OAuth2AccessToken) var3.next();
            if (token != null) {
                tokens.add(token);
            }
        }

        return tokens;
    }

    protected String extractTokenKey(String value) {
        if (value == null) {
            return null;
        } else {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException var5) {
                throw new IllegalStateException("MD5 algorithm not available.  Fatal (should be in the JDK).");
            }

            try {
                byte[] bytes = digest.digest(value.getBytes("UTF-8"));
                return String.format("%032x", new BigInteger(1, bytes));
            } catch (UnsupportedEncodingException var4) {
                throw new IllegalStateException("UTF-8 encoding not available.  Fatal (should be in the JDK).");
            }
        }
    }

    protected byte[] serializeAccessToken(OAuth2AccessToken token) {
        return SerializationUtils.serialize(token);
    }

    protected byte[] serializeRefreshToken(OAuth2RefreshToken token) {
        return SerializationUtils.serialize(token);
    }

    protected byte[] serializeAuthentication(OAuth2Authentication authentication) {
        return SerializationUtils.serialize(authentication);
    }

    protected OAuth2AccessToken deserializeAccessToken(byte[] token) {
        return (OAuth2AccessToken) SerializationUtils.deserialize(token);
    }

    protected OAuth2RefreshToken deserializeRefreshToken(byte[] token) {
        return (OAuth2RefreshToken) SerializationUtils.deserialize(token);
    }

    protected OAuth2Authentication deserializeAuthentication(byte[] authentication) {
        return (OAuth2Authentication) SerializationUtils.deserialize(authentication);
    }

    public void setInsertAccessTokenSql(String insertAccessTokenSql) {
        this.insertAccessTokenSql = insertAccessTokenSql;
    }

    public void setSelectAccessTokenSql(String selectAccessTokenSql) {
        this.selectAccessTokenSql = selectAccessTokenSql;
    }

    public void setDeleteAccessTokenSql(String deleteAccessTokenSql) {
        this.deleteAccessTokenSql = deleteAccessTokenSql;
    }

    public void setInsertRefreshTokenSql(String insertRefreshTokenSql) {
        this.insertRefreshTokenSql = insertRefreshTokenSql;
    }

    public void setSelectRefreshTokenSql(String selectRefreshTokenSql) {
        this.selectRefreshTokenSql = selectRefreshTokenSql;
    }

    public void setDeleteRefreshTokenSql(String deleteRefreshTokenSql) {
        this.deleteRefreshTokenSql = deleteRefreshTokenSql;
    }

    public void setSelectAccessTokenAuthenticationSql(String selectAccessTokenAuthenticationSql) {
        this.selectAccessTokenAuthenticationSql = selectAccessTokenAuthenticationSql;
    }

    public void setSelectRefreshTokenAuthenticationSql(String selectRefreshTokenAuthenticationSql) {
        this.selectRefreshTokenAuthenticationSql = selectRefreshTokenAuthenticationSql;
    }

    public void setSelectAccessTokenFromAuthenticationSql(String selectAccessTokenFromAuthenticationSql) {
        this.selectAccessTokenFromAuthenticationSql = selectAccessTokenFromAuthenticationSql;
    }

    public void setDeleteAccessTokenFromRefreshTokenSql(String deleteAccessTokenFromRefreshTokenSql) {
        this.deleteAccessTokenFromRefreshTokenSql = deleteAccessTokenFromRefreshTokenSql;
    }

    public void setSelectAccessTokensFromUserNameSql(String selectAccessTokensFromUserNameSql) {
        this.selectAccessTokensFromUserNameSql = selectAccessTokensFromUserNameSql;
    }

    public void setSelectAccessTokensFromUserNameAndClientIdSql(String selectAccessTokensFromUserNameAndClientIdSql) {
        this.selectAccessTokensFromUserNameAndClientIdSql = selectAccessTokensFromUserNameAndClientIdSql;
    }

    public void setSelectAccessTokensFromClientIdSql(String selectAccessTokensFromClientIdSql) {
        this.selectAccessTokensFromClientIdSql = selectAccessTokensFromClientIdSql;
    }

    /**
     * private String username;
     * private String clientId;
     * private String ipAddress;
     * private String accessToken;
     * private Timestamp loginAt;
     * private Timestamp expiredAt;
     *
     * @param params
     * @return
     */
    @Override
    public List<OauthAccessTokenExtended> datatables(DataTablesRequest<OauthAccessTokenExtended> params) {
        MapSqlParameterSource map = new MapSqlParameterSource();
        StringBuilder sb = new StringBuilder("select auth_id    as authentication_id,\n" +
                "       token_id   as token_id,\n" +
                "       token      as access_token,\n" +
                "       user_name   as username,\n" +
                "       client_id  as client_id,\n" +
                "       ip_address as ip_address,\n" +
                "       login_at   as login_time\n" +
                "from oauth.access_token where 1=1 ");

        OauthAccessTokenExtended value = params.getValue();
        if (StringUtils.isNotBlank(value.getClientId())) {
            sb.append(" and client_id like :clientId ");
            map.addValue("clientId", new StringBuilder("%").append(value.getClientId()).append("%").toString());
        }

        if (StringUtils.isNotBlank(value.getUsername())) {
            sb.append(" and user_name like :userName ");
            map.addValue("userName", new StringBuilder("%").append(value.getUsername()).append("%").toString());
        }

        if (StringUtils.isNotBlank(value.getIpAddress())) {
            sb.append(" and ip_address = :ipAddress ");
            map.addValue("ipAddress", value.getIpAddress());
        }

        if (params.getColOrder() != null) {
            switch (params.getColOrder().intValue()) {
                case 0:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        sb.append(" order by user_name asc ");
                    else sb.append(" order by user_name desc ");
                    break;
                case 1:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        sb.append(" order by client_id asc ");
                    else sb.append(" order by client_id desc ");
                    break;
                case 2:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        sb.append(" order by ip_address asc ");
                    else sb.append(" order by ip_address desc ");
                    break;
                case 3:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        sb.append(" order by login_time asc ");
                    else sb.append(" order by login_time desc ");
                    break;
            }
        }

        sb.append(" limit :limit ").append(" offset :offset ");
        map.addValue("limit", params.getLength());
        map.addValue("offset", params.getStart());

        List<OauthAccessTokenExtended> list = this.namedJdbcTemplate.query(sb.toString(), map, (resultSet, i) -> {
            try {
                OAuth2AccessToken oauth2AccessToken = JdbcTokenStoreCustomizer.this.deserializeAccessToken(resultSet.getBytes("access_token"));
                return new OauthAccessTokenExtended(
                        resultSet.getString("username"),
                        resultSet.getString("client_id"),
                        resultSet.getString("ip_address"),
                        oauth2AccessToken.getValue(),
                        resultSet.getTimestamp("login_time"),
                        new Timestamp(oauth2AccessToken.getExpiration().getTime())
                );
            } catch (IllegalArgumentException var5) {
                String token = resultSet.getString("token_id");
                JdbcTokenStoreCustomizer.this.jdbcTemplate.update(JdbcTokenStoreCustomizer.this.deleteAccessTokenSql, token);
                return null;
            }
        });
        return list;
    }

    @Override
    public Long datatables(OauthAccessTokenExtended value) {
        StringBuilder sb = new StringBuilder("select count(*) as rows from oauth.access_token where 1=1 ");
        MapSqlParameterSource map = new MapSqlParameterSource();

        if (StringUtils.isNotBlank(value.getClientId())) {
            sb.append(" and client_id like :clientId ");
            map.addValue("clientId", new StringBuilder("%").append(value.getClientId()).append("%").toString());
        }

        if (StringUtils.isNotBlank(value.getUsername())) {
            sb.append(" and user_name like :userName ");
            map.addValue("userName", new StringBuilder("%").append(value.getUsername()).append("%").toString());
        }

        if (StringUtils.isNotBlank(value.getIpAddress())) {
            sb.append(" and ip_address = :ipAddress ");
            map.addValue("ipAddress", value.getIpAddress());
        }

        Long row = this.namedJdbcTemplate.queryForObject(sb.toString(), map, new RowMapper<Long>() {
            @Override
            public Long mapRow(ResultSet resultSet, int i) throws SQLException {
                return resultSet.getLong("rows");
            }
        });
        return row;
    }

    /**
     * private String username;
     * private String clientId;
     * private String ipAddress;
     * private String accessToken;
     * private Timestamp loginAt;
     * private Timestamp expiredAt;
     * private boolean logout;
     * private Timestamp logoutAt;
     * private String logoutBy;
     *
     * @param username
     * @param clientId
     * @param params
     * @return
     */
    public List<OauthAccessTokenHistory> historyByUserAndClientIdDatatables(
            String username,
            String clientId,
            DataTablesRequest<OauthAccessTokenHistory> params) {
        MapSqlParameterSource map = new MapSqlParameterSource();
        StringBuilder queryBuilder = new StringBuilder("select access_id,\n" +
                "       token,\n" +
                "       client_id,\n" +
                "       ip_address,\n" +
                "       user_name,\n" +
                "       login_at,\n" +
                "       is_logout,\n" +
                "       logout_at,\n" +
                "       logout_by\n" +
                "from oauth.history_access_token\n" +
                "where 1 = 1\n" +
                "  and client_id = :clientId\n" +
                "  and user_name = :userName ");
        map.addValue("clientId", clientId);
        map.addValue("userName", username);

        OauthAccessTokenHistory value = params.getValue();
        if (value.getLogout() != null) {
            queryBuilder.append(" and is_logout = :isLogout ");
            map.addValue("isLogout", value.getLogout());
        }

        if (StringUtils.isNotBlank(value.getIpAddress())) {
            queryBuilder.append(" and ip_address like :ipAddress ");
            map.addValue("ipAddress", new StringBuilder("%").append(value.getIpAddress()).append("%").toString());
        }

        if (params.getColOrder() != null) {
            switch (params.getColOrder().intValue()) {
                case 0:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by user_name asc ");
                    else queryBuilder.append(" order by user_name desc ");
                    break;
                case 1:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by client_id asc ");
                    else queryBuilder.append(" order by client_id desc ");
                    break;
                case 2:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by ip_address asc ");
                    else queryBuilder.append(" order by ip_address desc ");
                    break;
                case 3:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by login_at asc ");
                    else queryBuilder.append(" order by login_at desc ");
                    break;
                case 4:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by is_logout asc ");
                    else queryBuilder.append(" order by is_logout desc ");
                    break;
                case 5:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by logout_at asc ");
                    else queryBuilder.append(" order by logout_at desc ");
                    break;
                case 6:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by logout_by asc ");
                    else queryBuilder.append(" order by logout_by desc ");
                    break;
            }
        }

        queryBuilder.append(" limit :limit ").append(" offset :offset ");
        map.addValue("limit", params.getLength());
        map.addValue("offset", params.getStart());

        List<OauthAccessTokenHistory> list = this.namedJdbcTemplate.query(queryBuilder.toString(), map, new RowMapper<OauthAccessTokenHistory>() {
            @Override
            public OauthAccessTokenHistory mapRow(ResultSet resultSet, int i) throws SQLException {
                try {
                    OAuth2AccessToken oauth2AccessToken = JdbcTokenStoreCustomizer.this.deserializeAccessToken(resultSet.getBytes("token"));
                    return new OauthAccessTokenHistory(
                            resultSet.getString("user_name"),
                            resultSet.getString("client_id"),
                            resultSet.getString("ip_address"),
                            oauth2AccessToken.getValue(),
                            resultSet.getTimestamp("login_at"),
                            new Timestamp(oauth2AccessToken.getExpiration().getTime()),
                            resultSet.getBoolean("is_logout"),
                            resultSet.getTimestamp("logout_at"),
                            resultSet.getString("logout_by")
                    );
                } catch (IllegalArgumentException var5) {
                    String token = resultSet.getString("access_id");
                    JdbcTokenStoreCustomizer.this.jdbcTemplate.update(JdbcTokenStoreCustomizer.this.deleteAccessTokenSql, token);
                    return null;
                }
            }
        });
        return list;

    }

    public Long historyByUsernameAndClientIdDatatables(String username, String clientId, OauthAccessTokenHistory param) {
        MapSqlParameterSource map = new MapSqlParameterSource();
        StringBuilder queryBuilder = new StringBuilder("select count(*) as rows\n" +
                "from oauth.history_access_token\n" +
                "where 1 = 1 \n" +
                "  and client_id = :clientId\n " +
                "  and user_name = :userName ");
        map.addValue("clientId", clientId);
        map.addValue("userName", username);

        if (param.getLogout() != null) {
            queryBuilder.append(" and is_logout = :isLogout ");
            map.addValue("isLogout", param.getLogout());
        }

        if (StringUtils.isNotBlank(param.getIpAddress())) {
            queryBuilder.append(" and ip_address like :ipAddress ");
            map.addValue("ipAddress", new StringBuilder("%").append(param.getIpAddress()).append("%").toString());
        }

        Long row = this.namedJdbcTemplate.queryForObject(queryBuilder.toString(), map, new RowMapper<Long>() {
            @Override
            public Long mapRow(ResultSet resultSet, int i) throws SQLException {
                return resultSet.getLong("rows");
            }
        });
        return row;
    }

    /**
     * private String username;
     * private String clientId;
     * private String ipAddress;
     * private String accessToken;
     * private Timestamp loginAt;
     * private Timestamp expiredAt;
     * private boolean logout;
     * private Timestamp logoutAt;
     * private String logoutBy;
     *
     * @param username
     * @param params
     * @return
     */
    public List<OauthAccessTokenHistory> historyByUserDatatables(
            String username,
            DataTablesRequest<OauthAccessTokenHistory> params) {
        MapSqlParameterSource map = new MapSqlParameterSource();
        StringBuilder queryBuilder = new StringBuilder("select access_id,\n" +
                "       token,\n" +
                "       client_id,\n" +
                "       ip_address,\n" +
                "       user_name,\n" +
                "       login_at,\n" +
                "       is_logout,\n" +
                "       logout_at,\n" +
                "       logout_by\n" +
                "from oauth.history_access_token\n" +
                "where 1 = 1\n" +
                "  and user_name = :userName ");
        map.addValue("userName", username);

        OauthAccessTokenHistory value = params.getValue();
        if (value.getLogout() != null) {
            queryBuilder.append(" and is_logout = :isLogout ");
            map.addValue("isLogout", value.getLogout());
        }

        if (StringUtils.isNotBlank(value.getClientId())) {
            queryBuilder.append(" and client_id like :clintId ");
            map.addValue("clintId", new StringBuilder("%").append(value.getClientId()).append("%").toString());
        }

        if (StringUtils.isNotBlank(value.getIpAddress())) {
            queryBuilder.append(" and ip_address like :ipAddress ");
            map.addValue("ipAddress", new StringBuilder("%").append(value.getIpAddress()).append("%").toString());
        }

        if (params.getColOrder() != null) {
            switch (params.getColOrder().intValue()) {
                case 0:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by user_name asc ");
                    else queryBuilder.append(" order by user_name desc ");
                    break;
                case 1:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by client_id asc ");
                    else queryBuilder.append(" order by client_id desc ");
                    break;
                case 2:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by ip_address asc ");
                    else queryBuilder.append(" order by ip_address desc ");
                    break;
                case 3:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by login_at asc ");
                    else queryBuilder.append(" order by login_at desc ");
                    break;
                case 4:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by is_logout asc ");
                    else queryBuilder.append(" order by is_logout desc ");
                    break;
                case 5:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by logout_at asc ");
                    else queryBuilder.append(" order by logout_at desc ");
                    break;
                case 6:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by logout_by asc ");
                    else queryBuilder.append(" order by logout_by desc ");
                    break;
            }
        }

        queryBuilder.append(" limit :limit ").append(" offset :offset ");
        map.addValue("limit", params.getLength());
        map.addValue("offset", params.getStart());

        List<OauthAccessTokenHistory> list = this.namedJdbcTemplate.query(queryBuilder.toString(), map, new RowMapper<OauthAccessTokenHistory>() {
            @Override
            public OauthAccessTokenHistory mapRow(ResultSet resultSet, int i) throws SQLException {
                try {
                    OAuth2AccessToken oauth2AccessToken = JdbcTokenStoreCustomizer.this.deserializeAccessToken(resultSet.getBytes("token"));
                    return new OauthAccessTokenHistory(
                            resultSet.getString("user_name"),
                            resultSet.getString("client_id"),
                            resultSet.getString("ip_address"),
                            oauth2AccessToken.getValue(),
                            resultSet.getTimestamp("login_at"),
                            new Timestamp(oauth2AccessToken.getExpiration().getTime()),
                            resultSet.getBoolean("is_logout"),
                            resultSet.getTimestamp("logout_at"),
                            resultSet.getString("logout_by")
                    );
                } catch (IllegalArgumentException var5) {
                    String token = resultSet.getString("access_id");
                    JdbcTokenStoreCustomizer.this.jdbcTemplate.update(JdbcTokenStoreCustomizer.this.deleteAccessTokenSql, token);
                    return null;
                }
            }
        });
        return list;

    }

    public Long historyByUsernameDatatables(String username, OauthAccessTokenHistory param) {
        MapSqlParameterSource map = new MapSqlParameterSource();
        StringBuilder queryBuilder = new StringBuilder("select count(*) as rows\n" +
                "from oauth.history_access_token\n" +
                "where 1 = 1 \n" +
                "  and user_name = :userName ");
        map.addValue("userName", username);

        if (param.getLogout() != null) {
            queryBuilder.append(" and is_logout = :isLogout ");
            map.addValue("isLogout", param.getLogout());
        }

        if (StringUtils.isNotBlank(param.getClientId())) {
            queryBuilder.append(" and client_id like :clintId ");
            map.addValue("clintId", new StringBuilder("%").append(param.getClientId()).append("%").toString());
        }

        if (StringUtils.isNotBlank(param.getIpAddress())) {
            queryBuilder.append(" and ip_address like :ipAddress ");
            map.addValue("ipAddress", new StringBuilder("%").append(param.getIpAddress()).append("%").toString());
        }

        Long row = this.namedJdbcTemplate.queryForObject(queryBuilder.toString(), map, new RowMapper<Long>() {
            @Override
            public Long mapRow(ResultSet resultSet, int i) throws SQLException {
                return resultSet.getLong("rows");
            }
        });
        return row;
    }


    /**
     * private String username;
     * private String clientId;
     * private String ipAddress;
     * private String accessToken;
     * private Timestamp loginAt;
     * private Timestamp expiredAt;
     * private boolean logout;
     * private Timestamp logoutAt;
     * private String logoutBy;
     *
     * @param clientId
     * @param params
     * @return
     */
    public List<OauthAccessTokenHistory> historyByClientIdDatatables(
            String clientId,
            DataTablesRequest<OauthAccessTokenHistory> params) {
        MapSqlParameterSource map = new MapSqlParameterSource();
        StringBuilder queryBuilder = new StringBuilder("select access_id,\n" +
                "       token,\n" +
                "       client_id,\n" +
                "       ip_address,\n" +
                "       user_name,\n" +
                "       login_at,\n" +
                "       is_logout,\n" +
                "       logout_at,\n" +
                "       logout_by\n" +
                "from oauth.history_access_token\n" +
                "where 1 = 1\n" +
                "  and client_id = :clientId ");
        map.addValue("clientId", clientId);

        OauthAccessTokenHistory value = params.getValue();
        if (value.getLogout() != null) {
            queryBuilder.append(" and is_logout = :isLogout ");
            map.addValue("isLogout", value.getLogout());
        }

        if (StringUtils.isNotBlank(value.getUsername())) {
            queryBuilder.append(" and user_name like :userName ");
            map.addValue("userName", new StringBuilder("%").append(value.getUsername()).append("%").toString());
        }

        if (StringUtils.isNotBlank(value.getIpAddress())) {
            queryBuilder.append(" and ip_address like :ipAddress ");
            map.addValue("ipAddress", new StringBuilder("%").append(value.getIpAddress()).append("%").toString());
        }

        if (params.getColOrder() != null) {
            switch (params.getColOrder().intValue()) {
                case 0:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by user_name asc ");
                    else queryBuilder.append(" order by user_name desc ");
                    break;
                case 1:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by client_id asc ");
                    else queryBuilder.append(" order by client_id desc ");
                    break;
                case 2:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by ip_address asc ");
                    else queryBuilder.append(" order by ip_address desc ");
                    break;
                case 3:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by login_at asc ");
                    else queryBuilder.append(" order by login_at desc ");
                    break;
                case 4:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by is_logout asc ");
                    else queryBuilder.append(" order by is_logout desc ");
                    break;
                case 5:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by logout_at asc ");
                    else queryBuilder.append(" order by logout_at desc ");
                    break;
                case 6:
                    if (params.getColDir().equalsIgnoreCase("asc"))
                        queryBuilder.append(" order by logout_by asc ");
                    else queryBuilder.append(" order by logout_by desc ");
                    break;
            }
        }

        queryBuilder.append(" limit :limit ").append(" offset :offset ");
        map.addValue("limit", params.getLength());
        map.addValue("offset", params.getStart());

        List<OauthAccessTokenHistory> list = this.namedJdbcTemplate.query(queryBuilder.toString(), map, new RowMapper<OauthAccessTokenHistory>() {
            @Override
            public OauthAccessTokenHistory mapRow(ResultSet resultSet, int i) throws SQLException {
                try {
                    OAuth2AccessToken oauth2AccessToken = JdbcTokenStoreCustomizer.this.deserializeAccessToken(resultSet.getBytes("token"));
                    return new OauthAccessTokenHistory(
                            resultSet.getString("user_name"),
                            resultSet.getString("client_id"),
                            resultSet.getString("ip_address"),
                            oauth2AccessToken.getValue(),
                            resultSet.getTimestamp("login_at"),
                            new Timestamp(oauth2AccessToken.getExpiration().getTime()),
                            resultSet.getBoolean("is_logout"),
                            resultSet.getTimestamp("logout_at"),
                            resultSet.getString("logout_by")
                    );
                } catch (IllegalArgumentException var5) {
                    String token = resultSet.getString("access_id");
                    JdbcTokenStoreCustomizer.this.jdbcTemplate.update(JdbcTokenStoreCustomizer.this.deleteAccessTokenSql, token);
                    return null;
                }
            }
        });
        return list;

    }

    public Long historyByClientIdDatatables(String clientId, OauthAccessTokenHistory param) {
        MapSqlParameterSource map = new MapSqlParameterSource();
        StringBuilder queryBuilder = new StringBuilder("select count(*) as rows\n" +
                "from oauth.history_access_token\n" +
                "where 1 = 1 \n" +
                "  and client_id = :clientId ");
        map.addValue("clientId", clientId);

        if (param.getLogout() != null) {
            queryBuilder.append(" and is_logout = :isLogout ");
            map.addValue("isLogout", param.getLogout());
        }

        if (StringUtils.isNotBlank(param.getUsername())) {
            queryBuilder.append(" and user_name like :userName ");
            map.addValue("userName", new StringBuilder("%").append(param.getUsername()).append("%").toString());
        }

        if (StringUtils.isNotBlank(param.getIpAddress())) {
            queryBuilder.append(" and ip_address like :ipAddress ");
            map.addValue("ipAddress", new StringBuilder("%").append(param.getIpAddress()).append("%").toString());
        }

        Long row = this.namedJdbcTemplate.queryForObject(queryBuilder.toString(), map, new RowMapper<Long>() {
            @Override
            public Long mapRow(ResultSet resultSet, int i) throws SQLException {
                return resultSet.getLong("rows");
            }
        });
        return row;
    }


    private final class SafeAccessTokenRowMapper implements RowMapper<OAuth2AccessToken> {
        private SafeAccessTokenRowMapper() {
        }

        public OAuth2AccessToken mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                return JdbcTokenStoreCustomizer.this.deserializeAccessToken(rs.getBytes(2));
            } catch (IllegalArgumentException var5) {
                String token = rs.getString(1);
                JdbcTokenStoreCustomizer.this.jdbcTemplate.update(JdbcTokenStoreCustomizer.this.deleteAccessTokenSql, token);
                return null;
            }
        }
    }


}
