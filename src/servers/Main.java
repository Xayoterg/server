package servers;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;


public class Main {
    static ArrayList<User> users = new ArrayList<>();
    static String db_url = "jdbc:mysql//127.0.0.1:3306/ChatGui";
    static String db_login = "root";
    static String db_pass = "";
    static Connection connection;

    public static void main(String[] args) {


        try {
            ServerSocket serverSocket = new ServerSocket(9443);  //запускаем сервер
            System.out.println("сервер запушен");
            Class.forName("com.mysql.cj.jdbc.Driver").getConstructor().newInstance();
            while (true) {
                Socket socket = serverSocket.accept();  //ожидаем подключение клиента
                System.out.println("клиент подключен");
                User carrenUser = new User(socket);
                users.add(carrenUser);

                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONParser jsonParser = new JSONParser();
                            boolean isAuth = false;
                            String name = "";

                            while (!isAuth) {
                                String result = "error";
                                String userData = carrenUser.getIs().readUTF();//считываем переданное сообщение
                                JSONObject autData = (JSONObject) jsonParser.parse(userData);
                                String login = autData.get("login").toString();
                                String pass = autData.get("pass").toString();
                                String tokenFrom = autData.get("token").toString();


                                connection = (Connection) DriverManager.getConnection(db_url, db_login, db_pass);
                                Statement statement = connection.createStatement();
                                ResultSet resultSet;
                                if (tokenFrom.equals("")) {
                                    resultSet = statement.executeQuery("SELECT *FROM users WHERE login='" + login + "'AND pass='" + pass + "'");

                                } else {
                                    resultSet = statement.executeQuery("SELECT *FROM users WHERE  token'" + tokenFrom + "'");
                                }
                                JSONObject jsonObject = new JSONObject();
                                if (resultSet.next()) { //если совпал пароль и логин
                                    int id = resultSet.getInt("id");
                                    carrenUser.setId(id);
                                    name = resultSet.getString("name");  //взяли имя из resultSet берет из бд
                                    isAuth = true;
                                    result = "success";
                                    String token = UUID.randomUUID().toString();
                                    statement.executeUpdate("UPDATE `users` SET `token`='" + token + "' WHERE id=" + id);
                                    jsonObject.put("token", token);
                                }

                                jsonObject.put("autResult", result);
                                carrenUser.getOut().writeUTF(jsonObject.toJSONString());//тправили результат авторизации
                            }


                            carrenUser.setName(name);
                            sendOnlineUsers();
                            sendHistoryChat(carrenUser);
                            while (true) {
                                JSONObject jsonObject = new JSONObject();
                                String message = carrenUser.getIs().readUTF();
                                JSONObject jsonMessage=(JSONObject) jsonParser.parse(message);
                                if (message.equals("/getOnlineUsers")) {
                                    JSONObject jsonObjectOnlineUsers = new JSONObject();
                                    JSONArray jsonArray = new JSONArray();
                                    for (User user : users) {
                                        jsonArray.add(user.getName());
                                    }
                                    jsonObjectOnlineUsers.put("users", jsonArray);
                                    carrenUser.getOut().writeUTF(jsonObjectOnlineUsers.toJSONString());


                                }else  if (jsonMessage.get("getHistoryMessage")!=null){   //клиент отправил идинтификатор
                                    int toId=Integer.parseInt(jsonMessage.get("getHistoryMessage").toString());
                                    int fromId=carrenUser.getId();
                                    Statement statement=connection.createStatement();
                                    JSONArray jsonMessages=new JSONArray(); //берет с базы и вытащил периписки с пользывателям отправляет клиенту
                                    ResultSet resultSet=statement.executeQuery("SELECT *FROM 'messages' WHERE from_id='"+fromId+"' AND to_id='"+toId+"' OR from_id='"+toId+"' AND to_id='"+fromId+"'");
                                    while (resultSet.next()){   //проверяем есть ли строка если нету ответ будет false
                                        JSONObject singleJsonMessage=new JSONObject();
                                        singleJsonMessage.put("msg",resultSet.getString("msg"));
                                        jsonMessages.add(singleJsonMessage); //кладем обьекты
                                    }
                                    JSONObject jsonResult=new JSONObject();
                                    jsonResult.put("privateMessages",jsonMessages); //отправил
                                    carrenUser.getOut().writeUTF(jsonResult.toJSONString());
                                } else {
                                    Statement statement = connection.createStatement();
                                    int id = carrenUser.getId();
                                    String msg = jsonMessage.get("msg").toString();
                                    int toId=Integer.parseInt(jsonMessage.get("to_id").toString());
                                    statement.executeUpdate("INSERT INTO `messages`( `msg`, `from_id`,'to_id') VALUES ('" + msg + "','" + id + "','"+toId+"')");
                                }
                                for (User user : users) {
                                    System.out.println(message.toUpperCase(Locale.ROOT));
                                    jsonObject.put("msg", name + ": " + message);
                                    if (!user.getUuid().toString().equals(carrenUser.getUuid().toString())) {
                                        user.getOut().writeUTF(jsonObject.toJSONString());
                                    }
                                }

                            }
                        } catch (Exception e) {
                            System.out.println("клиент отключился");
                            sendOnlineUsers();

                            users.remove(carrenUser);
                        }

                    }
                });
                thread.start();

            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendHistoryChat(User user) throws Exception {  //отправка архивных сообщений
        Statement statement = connection.createStatement();//обращяемся к бд   обращяемся сразу к двум таблицам
        ResultSet resultSet = statement.executeQuery("SELECT  users.id,users.name,message.msg FROM users,messeges WHERE users.id=messages.from_id AND to_id=0");
        JSONObject jsonObject = new JSONObject();
        JSONArray messages = new JSONArray(); //до терхпор пока resultSet ровняется true
        while (resultSet.next()) {
            JSONObject message = new JSONObject();//покуем в json имя,столбец name и id и сообщение
            message.put("name", resultSet.getString("name"));
            message.put("user_id", resultSet.getString("id"));
            message.put("msg", resultSet.getString("msg"));
            messages.add(message);  //добавить обьект в массив
        }
        jsonObject.put("messages", messages);
        user.getOut().writeUTF(jsonObject.toJSONString()); //отправляем к клиенту
    }

    public static void sendOnlineUsers() {   //рассылка онлайн пользывателей
        JSONArray userList = new JSONArray();
        for (User user : users) {
            JSONObject jsonUserInfo = new JSONObject();
            jsonUserInfo.put("name", user.getName());
            jsonUserInfo.put("id", user.getId());
            userList.add(jsonUserInfo);

        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("users", userList);
        for (User user : users) {
            try {
                user.getOut().writeUTF(jsonObject.toJSONString());

            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

}

