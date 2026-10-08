package com.ticketing.identity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

import static com.ticketing.identity.IdentityModels.*;

@Mapper
interface IdentityMapper {
    @Select("SELECT * FROM account WHERE login_name=#{loginName}")
    Account accountByLogin(String loginName);

    // A repeat seed never overwrites credentials, role, enablement, or timestamps.
    @Insert("""
            INSERT INTO account (login_name, password_hash, role, enabled, created_at, updated_at)
            VALUES (#{loginName}, #{passwordHash}, #{role}, TRUE, #{at}, #{at})
            ON DUPLICATE KEY UPDATE id=id
            """)
    int ensureAccount(@Param("loginName") String loginName, @Param("passwordHash") String passwordHash,
                      @Param("role") Role role, @Param("at") Instant at);

    @Insert("""
            INSERT INTO demo_login_token (token_digest, account_id, created_at, expires_at)
            VALUES (#{digest}, #{accountId}, #{createdAt}, #{expiresAt})
            """)
    int insertToken(@Param("digest") String digest, @Param("accountId") long accountId,
                    @Param("createdAt") Instant createdAt, @Param("expiresAt") Instant expiresAt);

    @Select("""
            SELECT a.id, a.login_name, a.role, a.enabled, t.expires_at
            FROM demo_login_token t JOIN account a ON a.id=t.account_id
            WHERE t.token_digest=#{digest}
            """)
    TokenAccount tokenAccount(String digest);
}
