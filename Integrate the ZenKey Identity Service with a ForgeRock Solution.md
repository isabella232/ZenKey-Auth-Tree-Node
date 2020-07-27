# Integrate the ZenKey Identity Service with a ForgeRock Solution

## ZenKey Identity Service Overview

ZenKey Identity Service is a unique, network-based identity solution that relies on data derived from wireless carriers to verify users. It provides a highly secure way for online services to verify their customers’ identities when they login from any mobile device and, lets people easily log into websites. This solution helps eliminate the need to remember, manage or update dozens of usernames and passwords that customers use today to log into web sites.

The ZenKey Identity Service integration with ForgeRock supports primary device flows, and secondary device flows using a browser on a laptop, desktop, or mobile device. For more information about these authentication flows visit [Server and Web Integration Guide](https://developer.myzenkey.com/web).

For information about how to integrate ZenKey into iOS and Android applications, visit the  [ZenKey Developer Resource Site](https://developer.myzenkey.com/).

## PreRequisites for integrating ZenKey and ForgeRock

 - You must have a ForgeRock instance or solution. For more information, see  [Create a New ForgeRock Account](https://backstage.forgerock.com/account/register).
 - You must be registered in the [ZenKey Developer Portal](https://portal.myzenkey.com/login) . Once your company is approved, make note of your client id and client secret. For more information, see the [ZenKey Portal User Guide](https://developer.myzenkey.com/portal/).
 - The wireless carriers must provision your client. Currently, the provisioning step may take a few days, but will be faster in the future.

## Install the ZenKey Authorization node

1. In a browser, navigate to [https://github.com/ForgeRock/ZenKey-Auth-Tree-Node](https://github.com/ForgeRock/ZenKey-Auth-Tree-Node).
2. Download the ZenKeyNode .jar file.
3. Install the .jar file on the web server that is hosting Access Management. On Tomcat, put the jar file in the lib directory.
For example: /tomcat/webapps/openam/WEB-INF/lib
4. Restart the server.

## Configure the ZenKey Authorization node

1. In a browser, navigate to [https://forgerock-dev.myzenkey.com/openam/console](https://forgerock-dev.myzenkey.com/openam/console).
2. Log into the site by providing your ForgeRock login id and password.
3. On the **Realms** page, click **Top Level Realm**.
4. On the **Realm Overview** page, click **Authentication** > **Trees**.
5. Click **Create Tree**.
6. In the **Tree Name** field, enter a name for the node.
7. Click **Create**.
8. In the left side panel under **Configuration**, type 'ZenKey' in the filter field.
9. When the ZenKey Auth Node appears in the left side panel, select the node and drag it to the main body of the page.
10. Connect the the Start node to the ZenKey node: Drag the green dot on the Start node to the ZenKey Auth node.
11. In the left side panel under **Configuration**, type 'Provision Dynamic Account' in the filter field.
12. When the 'Provision Dynamic Account' node appears in the left side panel, select the node and drag it to the main body of the page.
13. Connect the 'No Account exists' dot on the ZenKey Auth Node to the left side dot on the 'Provision Dynamic Account' node.
14. In the left side panel under **Configuration**, type 'Success' in the filter field.
15. When the 'Success' node appears in the left side panel, select the node and drag it to the main body of the page.
16. Connect the 'Account exists' dot on the ZenKey Auth Node to the left side dot on the 'Success' node.
17. Connect the right side dot on the 'Provision Dynamic Account' node to the left side dot on the 'Success' node.
18. Delete the 'Failure' node.
19. Click **Save**.

## Add Developer Portal credentials to the ZenKey Auth Node

1. Select the ZenKey Auth Node.
2. On the right side of the page, scroll down until you see the **Client ID** field. 
3. Enter your client ID and client secret, from the ZenKey Developer Portal dashboard, into the  **Client ID** and the **Client Secret** fields. Afterwards, the page auto-populates the **Carrier Discovery URL**, **OIDC Provider Config URL**, and the **Redirect URL** fields. You configure the Redirect URL in the Developer Portal.
4. Add scopes for app and site users: On the right side of the page, in the **OAuth** field, enter one for more of the following scopes:
    - openid
    - profile name
    - email
    - phone
5. Click **Save**.

## Configure Account and Attribute mappings

1. Select the ZenKey Auth Node.
2. On the right side of the page, scroll down to the **Account Mapper Configuration** section.
3. Verify the value in the **KEY** field is 'email'.
4. Verify the value in the **VALUE** field is 'uid'.
5. Scroll down to the **Attribute Mapper Configuration** section.
6. Verify the value in the **KEY** field is 'email'.
7. Verify the value in the **VALUE** field is 'uid'.
8. Click **Save**.

## Choose an Authentication Service

Set ZenKey as the authentication service for all websites that connect to the Access Management server.

1. Click **Top Level Realm**.
2. On the **Realms** page, navigate to **Realm Overview** > **Authentication** > **Settings**.
3. In the **Organization Authentication Configuration** drop down field, select 'ZenKey'.
4. Click **Save Changes**.
5. Logout.

## Test the ZenKey ForgeRock Configuration

You should verify that you correctly integrated ZenKey with your ForgeRock instance.

Steps for testing the ZenKey and ForgeRock configuration

1. Open one of your ForgeRock enabled websites.
2. You should be able to use ZenKey to log into the site.
