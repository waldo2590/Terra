package io.left.rightmesh.core.module.aa.ldcp;

import io.left.rightmesh.aa.ldcp.ApiPaths;
import io.left.rightmesh.ldcp.LdcpRequest;
import io.left.rightmesh.ldcp.LdcpServer;
import io.left.rightmesh.ldcp.RequestHandler;
import io.left.rightmesh.ldcp.Router;
import io.left.rightmesh.ldcp.messages.RequestMessage;
import io.left.rightmesh.ldcp.messages.ResponseMessage;
import io.left.rightmesh.libdtn.common.ExtensionToolbox;
import io.left.rightmesh.libdtn.common.data.Bundle;
import io.left.rightmesh.libdtn.common.data.blob.BlobFactory;
import io.left.rightmesh.libdtn.common.data.blob.NullBlob;
import io.left.rightmesh.libdtn.common.utils.Log;
import io.left.rightmesh.libdtn.core.api.ConfigurationApi;
import io.left.rightmesh.libdtn.core.api.DeliveryApi;
import io.left.rightmesh.libdtn.core.api.RegistrarApi;
import io.left.rightmesh.libdtn.core.spi.aa.ActiveRegistrationCallback;
import io.left.rightmesh.libdtn.core.spi.aa.ApplicationAgentAdapterSpi;
import io.reactivex.Completable;

/**
 * AAModuleLdcp is an module that is used to enable register and manage application agents
 * over LDCP.
 *
 * @author Lucien Loiseau on 25/10/18.
 */
public class AaModuleLdcp implements ApplicationAgentAdapterSpi {

    private static final String TAG = "ldcp";

    private static class RequestException extends Exception {
        RequestException(String msg) {
            super("Request: " + msg);
        }
    }

    RegistrarApi registrar;
    Log logger;
    ExtensionToolbox toolbox;

    public AaModuleLdcp() {
    }

    @Override
    public String getModuleName() {
        return TAG;
    }

    @Override
    public void init(RegistrarApi api,
                     ConfigurationApi conf,
                     Log logger,
                     ExtensionToolbox toolbox,
                     BlobFactory factory) {
        this.registrar = api;
        this.logger = logger;
        this.toolbox = toolbox;

        int port = conf.getModuleConf(getModuleName(),
                Configuration.LDCP_TCP_PORT,
                Configuration.LDCP_TCP_PORT_DEFAULT).value();
        logger.i(TAG, "starting a ldcp server on port " + port);
        new LdcpServer().start(port, toolbox, factory, logger,
                Router.create()
                        .GET(ApiPaths.ClientToDaemonLdcpPathVersion1.ISREGISTERED.path,
                                isregistered)
                        .POST(ApiPaths.ClientToDaemonLdcpPathVersion1.REGISTER.path,
                                register)
                        .POST(ApiPaths.ClientToDaemonLdcpPathVersion1.UPDATE.path,
                                update)
                        .POST(ApiPaths.ClientToDaemonLdcpPathVersion1.UNREGISTER.path,
                                unregister)
                        .GET(ApiPaths.ClientToDaemonLdcpPathVersion1.GETBUNDLE.path,
                                get)
                        .GET(ApiPaths.ClientToDaemonLdcpPathVersion1.FETCHBUNDLE.path,
                                fetch)
                        .POST(ApiPaths.ClientToDaemonLdcpPathVersion1.DISPATCH.path,
                                dispatch));
    }

    private String checkField(RequestMessage req, String key) throws RequestException {
        if (!req.fields.containsKey(key)) {
            logger.v(TAG, ". missing field " + key);
            throw new RequestException("missing field");
        }
        logger.v(TAG, ". field " + key + "=" + req.fields.get(key));
        return req.fields.get(key);
    }

    private ActiveRegistrationCallback deliverCallback(String sink, String host, int port) {
        return (bundle) ->
                LdcpRequest.POST(ApiPaths.DaemonToClientLdcpPathVersion1.DELIVER.path)
                        .setBundle(bundle)
                        .send(host, port, toolbox, NullBlob::new, logger)
                        .doOnError(d -> {
                            try {
                                /* connection fail - remote is no longer active */
                                registrar.setPassive(sink);
                            } catch (RegistrarApi.RegistrarException re) {
                                /* ignore */
                            }
                        })
                        .flatMapCompletable(d ->
                                d.code.equals(ResponseMessage.ResponseCode.ERROR)
                                        ? Completable.error(new DeliveryApi.DeliveryRefused())
                                        : Completable.complete());
    }

    private RequestHandler isregistered = (req, res) ->
            Completable.create(s -> {
                try {
                    String sink = checkField(req, "sink");
                    res.setCode(registrar.isRegistered(sink)
                            ? ResponseMessage.ResponseCode.OK
                            : ResponseMessage.ResponseCode.ERROR);
                    s.onComplete();
                } catch (RegistrarApi.RegistrarException | RequestException re) {
                    s.onError(re);
                }
            });

    private RequestHandler register = (req, res) ->
            Completable.create(s -> {
                try {
                    String sink = checkField(req, "sink");
                    boolean active = checkField(req, "active").equals("true");

                    if (active) {
                        String host = checkField(req, "active-host");
                        int port = Integer.valueOf(checkField(req, "active-port"));

                        String cookie = registrar.register(sink, deliverCallback(sink, host, port));
                        res.setCode(ResponseMessage.ResponseCode.OK);
                        res.setHeader("cookie", cookie);
                        s.onComplete();
                    } else {
                        String cookie = registrar.register(sink);
                        res.setCode(ResponseMessage.ResponseCode.OK);
                        res.setHeader("cookie", cookie);
                        s.onComplete();
                    }
                } catch (RegistrarApi.RegistrarException | RequestException re) {
                    s.onError(re);
                }
            });


    private RequestHandler update = (req, res) ->
            Completable.create(s -> {
                try {
                    String sink = checkField(req, "sink");
                    String cookie = checkField(req, "cookie");
                    boolean active = checkField(req, "active").equals("true");

                    if (active) {
                        String host = checkField(req, "active-host");
                        int port = Integer.valueOf(checkField(req, "active-port"));

                        registrar.setActive(sink, cookie, deliverCallback(sink, host, port));
                        res.setCode(ResponseMessage.ResponseCode.OK);
                        s.onComplete();
                    } else {
                        registrar.setPassive(sink, cookie);
                        res.setCode(ResponseMessage.ResponseCode.OK);
                        s.onComplete();
                    }
                } catch (RegistrarApi.RegistrarException | RequestException re) {
                    s.onError(re);
                }
            });

    private RequestHandler unregister = (req, res) ->
            Completable.create(s -> {
                try {
                    String sink = checkField(req, "sink");
                    String cookie = checkField(req, "cookie");

                    res.setCode(registrar.unregister(sink, cookie)
                            ? ResponseMessage.ResponseCode.OK
                            : ResponseMessage.ResponseCode.ERROR);
                    s.onComplete();
                } catch (RegistrarApi.RegistrarException | RequestException re) {
                    s.onError(re);
                }
            });

    private RequestHandler get = (req, res) ->
            Completable.create(s -> {
                try {
                    String sink = checkField(req, "sink");
                    String cookie = checkField(req, "cookie");
                    String bid = checkField(req, "bundle-id");

                    Bundle bundle = registrar.get(sink, cookie, bid);
                    if (bundle != null) {
                        res.setCode(ResponseMessage.ResponseCode.OK);
                        res.setBundle(bundle);
                    } else {
                        res.setCode(ResponseMessage.ResponseCode.ERROR);
                    }
                    s.onComplete();
                } catch (RegistrarApi.RegistrarException | RequestException re) {
                    s.onError(re);
                }
            });

    private RequestHandler fetch = (req, res) ->
            Completable.create(s -> {
                try {
                    String sink = checkField(req, "sink");
                    String cookie = checkField(req, "cookie");
                    String bid = checkField(req, "bundle-id");

                    Bundle bundle = registrar.fetch(sink, cookie, bid);
                    if (bundle != null) {
                        res.setCode(ResponseMessage.ResponseCode.OK);
                        res.setBundle(bundle);
                    } else {
                        res.setCode(ResponseMessage.ResponseCode.ERROR);
                    }
                    s.onComplete();
                } catch (RegistrarApi.RegistrarException | RequestException re) {
                    s.onError(re);
                }
            });

    private RequestHandler dispatch = (req, res) ->
            Completable.create(s -> {
                try {
                    res.setCode(registrar.send(req.bundle)
                            ? ResponseMessage.ResponseCode.OK
                            : ResponseMessage.ResponseCode.ERROR);
                    s.onComplete();
                } catch (RegistrarApi.RegistrarException re) {
                    logger.w(TAG, "registrar exception: " + re.getMessage());
                    s.onError(re);
                }
            });
}
