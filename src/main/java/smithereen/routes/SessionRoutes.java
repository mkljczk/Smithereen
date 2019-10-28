package smithereen.routes;

import org.jtwig.JtwigModel;

import java.sql.SQLException;
import java.util.Base64;
import java.util.Locale;

import smithereen.Utils;
import smithereen.data.Account;
import smithereen.storage.SessionStorage;
import smithereen.storage.UserStorage;
import spark.Request;
import spark.Response;

public class SessionRoutes{
	private static void setupSessionWithAccount(Request req, Response resp, Account acc) throws SQLException{
		req.session(true).attribute("account", acc);
		String psid=SessionStorage.putNewSession(req.session());
		req.session().attribute("csrf", Utils.csrfTokenFromSessionID(Base64.getDecoder().decode(psid)));
		req.session().attribute("locale", Locale.forLanguageTag("ru"));
		resp.cookie("/", "psid", psid, 10*365*24*60*60, false);
	}

	public static Object login(Request req, Response resp) throws SQLException{
		Account acc=SessionStorage.getAccountForUsernameAndPassword(req.queryParams("username"), req.queryParams("password"));
		if(acc!=null){
			setupSessionWithAccount(req, resp, acc);
			resp.redirect("/feed");
			return "";
		}
		return "Invalid credentials";
	}

	public static Object logout(Request req, Response resp) throws SQLException{
		if(Utils.requireAccount(req, resp) && Utils.verifyCSRF(req, resp)){
			SessionStorage.deleteSession(req.cookie("psid"));
			resp.removeCookie("psid");
			req.session().removeAttribute("account");
			req.session().removeAttribute("csrf");
			resp.redirect("/");
			return "";
		}
		return null;
	}

	private static String regError(Request req, String errKey){
		JtwigModel model=JtwigModel.newModel()
				.with("message", Utils.lang(req).get(errKey))
				.with("username", req.queryParams("username"))
				.with("password", req.queryParams("password"))
				.with("password2", req.queryParams("password2"))
				.with("email", req.queryParams("email"))
				.with("first_name", req.queryParams("first_name"))
				.with("last_name", req.queryParams("last_name"))
				.with("invite", req.queryParams("invite"));
		return Utils.renderTemplate(req, "register", model);
	}

	public static Object register(Request req, Response resp) throws SQLException{
		String username=req.queryParams("username");
		String password=req.queryParams("password");
		String password2=req.queryParams("password2");
		String email=req.queryParams("email");
		String first=req.queryParams("first_name");
		String last=req.queryParams("last_name");
		String invite=req.queryParams("invite");

		if(!Utils.isValidUsername(username))
			return regError(req, "err_reg_invalid_username");
		if(Utils.isReservedUsername(username))
			return regError(req, "err_reg_reserved_username");
		if(UserStorage.getByUsername(username)!=null)
			return regError(req, "err_reg_username_taken");
		if(password.length()<4)
			return regError(req, "err_password_short");
		if(!password.equals(password2))
			return regError(req, "err_passwords_dont_match");
		if(!Utils.isValidEmail(email))
			return regError(req, "err_invalid_email");
		if(first.length()<2)
			return regError(req, "err_name_too_short");
		if(!invite.matches("[A-F0-9]{32}"))
			return regError(req, "err_invalid_invitation");

		SessionStorage.SignupResult res=SessionStorage.registerNewAccount(username, password, email, first, last, invite);
		if(res==SessionStorage.SignupResult.SUCCESS){
			Account acc=SessionStorage.getAccountForUsernameAndPassword(username, password);
			setupSessionWithAccount(req, resp, acc);
			resp.redirect("/feed");
		}else if(res==SessionStorage.SignupResult.USERNAME_TAKEN){
			return regError(req, "err_reg_username_taken");
		}else if(res==SessionStorage.SignupResult.INVITE_INVALID){
			return regError(req, "err_invalid_invitation");
		}

		return "";
	}
}
