## glasnik

`glasnik` is a simple command-line REST client. The main design goal is to make it very quick to set up and call an API without typing a bunch of parameters every time.

There's no database or fancy storage. Everything is stored in regular files under `~/.glasnik`.

## how it works

If you type `glasnik help` (or `glasnik --help` or `glasnik -h`), you get the following message:

```
usage:
glasnik [status] - show status (current workspace and vars)
glasnik use {workspace_name} - switch to a workspace (default vars}
glasnik use {workspace_name}.{vars_name} - switch to a workspace and vars
glasnik use .{vars_name} - switch to vars in the current workspace
glasnik add {workspace_name}.{vars_name} - add a new workspace and/or vars file
glasnik add .{vars_name} - add a new or vars file in the current workspace
glasnik delete {workspace_name} - delete a workspace
glasnik delete {workspace_name}.{vars_name} - delete a vars file in the specified workspace
glasnik delete .{vars_name} - delete a vars file in the current workspace
glasnik edit - edit calls in the current workspace
glasnik edit {workspace_name}.{vars_name} - edit vars in the specified workspace
glasnik edit .{vars_name} - edit vars in the current workspace
glasnik update - update vars in the current workspace to include anything in calls
glasnik list - list workspaces
glasnik calls - list calls in the current workspace
glasnik clear - clear extracted vars in the current workspace
glasnik {call_name} [{body_filename}] - issue a call in the current workspace
glasnik help|-h|--help - show this message
```

`glasnik` uses "workspaces" and "vars" to manage information. A workspace is just a set of calls (usually to a particular REST API). When defining calls, you can use values in curly braces, which get filled in from the current vars file. In your calls, you can also specify values to be extracted from responses. These are treated like vars. They're persisted until cleared, and their current values get substituted into URLs, headers and bodies in the same way as vars.

Calls are stored in `~/.glasnik/{workspace_name}/calls.yml`.

Vars are stored in `~/.glasnik/{workspace_name}/{vars_name}.properties`

At any given time, `glasnik` has a "current" workspace and vars file, which you can change with the `use` command.

## example

Here's a real-life example. Let's say I worked at ChartHop, and I wanted to call the ChartHop API both against my local development environment and against a remote server. I would run `glasnik add charthop.local` to add the workspace (and "local" vars). Since I have no current workspace yet, it would use the one I just created, and `glasnik status` would give me: 

```
workspace: charthop
vars file: local
```

Then I could run `glasnik edit` to edit `~/.glasnik/charthop/calls.yml`, and put in the following info:

```yaml
---
login:
  url: "{protocol}://{host}/oauth/token"
  method: "POST"
  contentType: "application/x-www-form-urlencoded"
  body: "grant_type=password&username={username}&password={password}"
  extracts:
  - from: "JSON_BODY"
    to: "token"
    value: "access_token"
org:
  url: "{protocol}://{host}/v1/org/{orgSlug}"
  headers:
    Authorization: "Bearer {token}"

```

There are two calls here: `login` and `org`. Between the two of these, all the possible properties of a call definition are used.

The `login` call is a `POST` that posts a form and extracts the bearer token from the JSON response. You can see that the `login` call expects a bunch of vars, like `protocol`, `host`, `username` and `password`. These will be defined in a vars file (if I run `glasnik update`, it will automatically add any new ones to my var files, with blank values).

The `org` call is a `GET` (it's the default method, so I don't need to specify it). It uses the `token` var that was extracted by the `login` call to build an authorization header. If I call `glasnik org` before I call `glasnik login`, I'll get an error, because the `token` var wouldn't exist yet.

Now I can run `glasnik update` to add any vars I used in `~/.glasnik/charthop/calls.yml` to the "local" vars file. Then I can run `glassnik edit .local` to edit the vars file. Initially, the `~/.glasnik/charthop/local.properties` file will look like this (the vars were added by `glasnik update`):

```
orgSlug=
protocol=
password=
host=
username=
```

I can edit it to set values for the vars:

```
orgSlug=national-nephelometer
protocol=http
password=12345
host=localhost\:8080
username=sheylin.i.jarville@nationalnephelometer.com
```

Now I can run `glasnik add .remote`, and then `glasnik edit .remote` to edit `~/.glasnik/charthop/remote.properties`. This time, I don't need to run `glasnik update` -- since the calls file was already filled in when I added "remote", the vars will be added automatically. I can now set different values for the vars:

```
orgSlug=industrial-tungsten
protocol=https
password=12345
host=some-dev-environment.charthop.com
username=ludie.z.piggens@industrialtungsten.com
```

You can see that these vars files define all the vars needed by my calls file, except for `token`, which is extracted when I run `glasnik login` (the current set of extracted vars will be stored in `~/.glasnik/charthop/charthop.yml`, and I can run `glasnik clear` to clear them).

To switch between "local" and "remote" vars, I would run `glasnik use .local` and `glasnik use .remote` respectively. When you switch to a different vars file in a workspace, the extracted vars are cleared. They're not cleared if you switch to an entirely different workspace.

The only major feature not covered in this example is request bodies. In the above `login` call, I included the body in the configuration for the call itself, since it's always the same. And all I have to do to login is run `glasnik login`. But I can also do posts with varying bodies.

When I added the `charthop` workspace, a `~/.glasnik/charthop/bodies` directory was automatically created. I can place a body file (like `some_post.json`) in this folder, and then use that body with something like `glasnik some_call some_post.json`. If you provide a body file name it will override any body defined in the call file itself. The content in any body file should be consistent with the `contentType` defined for the call you're making.



