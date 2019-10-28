package smithereen.storage;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Locale;

import smithereen.Utils;
import smithereen.data.Account;
import spark.Session;

public class SessionStorage{

	private static SecureRandom random=new SecureRandom();

	public static String putNewSession(@NotNull Session sess) throws SQLException{
		byte[] sid=new byte[64];
		Account account=sess.attribute("account");
		if(account==null)
			throw new IllegalArgumentException("putNewSession requires a logged in session");
		random.nextBytes(sid);
		PreparedStatement stmt=DatabaseConnectionManager.getConnection().prepareStatement("INSERT INTO `sessions` (`id`, `account_id`) VALUES (?, ?)");
		stmt.setBytes(1, sid);
		stmt.setInt(2, account.id);
		stmt.execute();
		return Base64.getEncoder().encodeToString(sid);
	}

	public static boolean fillSession(String psid, Session sess) throws SQLException{
		byte[] sid;
		try{
			sid=Base64.getDecoder().decode(psid);
		}catch(Exception x){
			return false;
		}
		if(sid.length!=64)
			return false;

		PreparedStatement stmt=DatabaseConnectionManager.getConnection().prepareStatement("SELECT * FROM `accounts` WHERE `id` IN (SELECT `account_id` FROM `sessions` WHERE `id`=?)");
		stmt.setBytes(1, sid);
		try(ResultSet res=stmt.executeQuery()){
			if(!res.first())
				return false;
			sess.attribute("account", Account.fromResultSet(res));
			sess.attribute("csrf", Utils.csrfTokenFromSessionID(sid));
			sess.attribute("locale", Locale.forLanguageTag("ru"));
		}
		return true;
	}

	public static Account getAccountForUsernameAndPassword(@NotNull String usernameOrEmail, @NotNull String password) throws SQLException{
		try{
			MessageDigest md=MessageDigest.getInstance("SHA-256");
			byte[] hashedPassword=md.digest(password.getBytes(StandardCharsets.UTF_8));
			PreparedStatement stmt;
			if(usernameOrEmail.contains("@")){
				stmt=DatabaseConnectionManager.getConnection().prepareStatement("SELECT * FROM `accounts` WHERE `email`=? AND `password`=?");
			}else{
				stmt=DatabaseConnectionManager.getConnection().prepareStatement("SELECT * FROM `accounts` WHERE `user_id` IN (SELECT `id` FROM `users` WHERE `username`=?) AND `password`=?");
			}
			stmt.setString(1, usernameOrEmail);
			stmt.setBytes(2, hashedPassword);
			try(ResultSet res=stmt.executeQuery()){
				if(res.first()){
					return Account.fromResultSet(res);
				}
				return null;
			}
		}catch(NoSuchAlgorithmException ignore){}
		throw new AssertionError();
	}

	public static void deleteSession(@NotNull String psid) throws SQLException{
		byte[] sid=Base64.getDecoder().decode(psid);
		if(sid.length!=64)
			return;

		PreparedStatement stmt=DatabaseConnectionManager.getConnection().prepareStatement("DELETE FROM `sessions` WHERE `id`=?");
		stmt.setBytes(1, sid);
		stmt.execute();
	}

	public static SignupResult registerNewAccount(@NotNull String username, @NotNull String password, @NotNull String email, @NotNull String firstName, @NotNull String lastName, @NotNull String invite) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		conn.createStatement().execute("START TRANSACTION");
		try{
			PreparedStatement stmt=conn.prepareStatement("UPDATE `signup_invitations` SET `signups_remaining`=`signups_remaining`-1 WHERE `signups_remaining`>0 AND `code`=?");
			stmt.setBytes(1, Utils.hexStringToByteArray(invite));
			if(stmt.executeUpdate()!=1){
				conn.createStatement().execute("ROLLBACK");
				return SignupResult.INVITE_INVALID;
			}

			stmt=conn.prepareStatement("SELECT `owner_id` FROM `signup_invitations` WHERE `code`=?");
			stmt.setBytes(1, Utils.hexStringToByteArray(invite));
			int inviterAccountID=0;
			try(ResultSet res=stmt.executeQuery()){
				res.first();
				inviterAccountID=res.getInt(1);
			}

			KeyPairGenerator kpg=KeyPairGenerator.getInstance("RSA");
			kpg.initialize(2048);
			KeyPair pair=kpg.generateKeyPair();

			stmt=conn.prepareStatement("INSERT INTO `users` (`fname`, `lname`, `username`, `public_key`, `private_key`) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
			stmt.setString(1, firstName);
			stmt.setString(2, lastName);
			stmt.setString(3, username);
			stmt.setBytes(4, pair.getPublic().getEncoded());
			stmt.setBytes(5, pair.getPrivate().getEncoded());
			stmt.execute();
			int userID;
			try(ResultSet res=stmt.getGeneratedKeys()){
				res.first();
				userID=res.getInt(1);
			}

			MessageDigest md=MessageDigest.getInstance("SHA-256");
			byte[] hashedPassword=md.digest(password.getBytes(StandardCharsets.UTF_8));
			stmt=conn.prepareStatement("INSERT INTO `accounts` (`user_id`, `email`, `password`, `invited_by`) VALUES (?, ?, ?, ?)");
			stmt.setInt(1, userID);
			stmt.setString(2, email);
			stmt.setBytes(3, hashedPassword);
			stmt.setInt(4, inviterAccountID);
			stmt.execute();

			int inviterUserID=0;
			stmt=conn.prepareStatement("SELECT `user_id` FROM `accounts` WHERE `id`=?");
			stmt.setInt(1, inviterAccountID);
			try(ResultSet res=stmt.executeQuery()){
				res.first();
				inviterUserID=res.getInt(1);
			}

			stmt=conn.prepareStatement("INSERT INTO `followings` (`follower_id`, `followee_id`, `mutual`) VALUES (?, ?, 1), (?, ?, 1)");
			stmt.setInt(1, inviterUserID);
			stmt.setInt(2, userID);
			stmt.setInt(3, userID);
			stmt.setInt(4, inviterUserID);
			stmt.execute();

			conn.createStatement().execute("COMMIT");
		}catch(SQLException x){
			conn.createStatement().execute("ROLLBACK");
			throw new SQLException(x);
		}catch(NoSuchAlgorithmException ignore){}
		return SignupResult.SUCCESS;
	}

	public static boolean updatePassword(int accountID, String oldPassword, String newPassword) throws SQLException{
		try{
			MessageDigest md=MessageDigest.getInstance("SHA-256");
			byte[] hashedOld=md.digest(oldPassword.getBytes(StandardCharsets.UTF_8));
			byte[] hashedNew=md.digest(newPassword.getBytes(StandardCharsets.UTF_8));
			Connection conn=DatabaseConnectionManager.getConnection();
			PreparedStatement stmt=conn.prepareStatement("UPDATE `accounts` SET `password`=? WHERE `id`=? AND `password`=?");
			stmt.setBytes(1, hashedNew);
			stmt.setInt(2, accountID);
			stmt.setBytes(3, hashedOld);
			return stmt.executeUpdate()==1;
		}catch(NoSuchAlgorithmException ignore){}
		return false;
	}

	public enum SignupResult{
		SUCCESS,
		USERNAME_TAKEN,
		INVITE_INVALID
	}
}
