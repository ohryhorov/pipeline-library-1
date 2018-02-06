package com.mirantis.mk
/**
 * Orchestration functions
 *
*/

def validateFoundationInfra(master, tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    salt.runSaltProcessStep(master, "I@salt:master ${tgt_extra}", 'cmd.run', ['salt-key'], null, true)
    salt.runSaltProcessStep(master, "I@salt:minion ${tgt_extra}", 'test.version', [], null, true)
    salt.runSaltProcessStep(master, "I@salt:master ${tgt_extra}", 'cmd.run', ['reclass-salt --top'], null, true)
    salt.runSaltProcessStep(master, "I@reclass:storage ${tgt_extra}", 'reclass.inventory', [], null, true)
    salt.runSaltProcessStep(master, "I@salt:minion ${tgt_extra}", 'state.show_top', [], null, true)
}

def installFoundationInfra(master, staticMgmtNet=false, tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    // NOTE(vsaienko) Apply reclass first, it may update cluster model
    // apply linux and salt.master salt.minion states afterwards to make sure
    // correct cluster model is used.
    salt.enforceState(master, "I@salt:master ${tgt_extra}", ['reclass'], true)

    salt.enforceState(master, "I@salt:master ${tgt_extra}", ['linux.system'], true)
    salt.enforceState(master, "I@salt:master ${tgt_extra}", ['salt.master'], true, false, null, false, 120, 2)
    salt.runSaltProcessStep(master, "* ${tgt_extra}", 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, "* ${tgt_extra}", 'saltutil.sync_all', [], null, true)

    salt.enforceState(master, "I@salt:master ${tgt_extra}", ['salt.minion'], true, false, null, false, 60, 2)
    salt.enforceState(master, "I@salt:master ${tgt_extra}", ['salt.minion'], true)
    salt.runSaltProcessStep(master, "* ${tgt_extra}", 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, "* ${tgt_extra}", 'saltutil.refresh_grains', [], null, true)
    salt.runSaltProcessStep(master, "* ${tgt_extra}", 'saltutil.sync_all', [], null, true)

    salt.enforceState(master, "* ${tgt_extra}", ['linux.system'], true)
    if (staticMgmtNet) {
        salt.runSaltProcessStep(master, "* ${tgt_extra}", 'cmd.shell', ["salt-call state.sls linux.network; salt-call service.restart salt-minion"], null, true, 60)
    }
    salt.enforceState(master, "I@linux:system ${tgt_extra}", ['linux', 'openssh', 'ntp'], true)
    salt.enforceState(master, "* ${tgt_extra}", ['salt.minion'], true, false, null, false, 60, 2)
    sleep(5)
    salt.enforceState(master, "* ${tgt_extra}", ['linux.network.host'], true)
}

def installFoundationInfraOnTarget(master, target, staticMgmtNet=false) {
    def salt = new com.mirantis.mk.Salt()

    salt.enforceState(master, 'I@salt:master', ['reclass'], true, false, null, false, 120, 2)

    salt.runSaltProcessStep(master, target, 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, target, 'saltutil.sync_all', [], null, true)

    salt.enforceState(master, target, ['linux.system'], true)
    if (staticMgmtNet) {
        salt.runSaltProcessStep(master, target, 'cmd.shell', ["salt-call state.sls linux.network; salt-call service.restart salt-minion"], null, true, 60)
    }
    salt.enforceState(master, target, ['salt.minion'], true, false, null, false, 60, 2)
    salt.enforceState(master, target, ['salt.minion'], true)

    salt.enforceState(master, target, ['linux', 'openssh', 'ntp'], true)
    sleep(5)
    salt.enforceState(master, target, ['linux.network.host'], true)
}

def installInfraKvm(master) {
    def salt = new com.mirantis.mk.Salt()

    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, 'I@linux:system', 'saltutil.sync_all', [], null, true)

    salt.enforceState(master, 'I@salt:control', ['salt.minion'], true, false, null, false, 60, 2)
    salt.enforceState(master, 'I@salt:control', ['linux.system', 'linux.network', 'ntp'], true)
    salt.enforceState(master, 'I@salt:control', 'libvirt', true)
    salt.enforceState(master, 'I@salt:control', 'salt.control', true)

    sleep(600)

    salt.runSaltProcessStep(master, '* and not kvm*', 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, '* and not kvm*', 'saltutil.sync_all', [], null, true)
}

def installInfra(master, tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    // Install glusterfs
    if (salt.testTarget(master, "I@glusterfs:server ${tgt_extra}")) {
        salt.enforceState(master, "I@glusterfs:server ${tgt_extra}", 'glusterfs.server.service', true)

        withEnv(['ASK_ON_ERROR=false']){
            retry(5) {
                salt.enforceState(master, "I@glusterfs:server and *01* ${tgt_extra}", 'glusterfs.server.setup', true)
            }
        }

        salt.runSaltProcessStep(master, "I@glusterfs:server ${tgt_extra}", 'cmd.run', ['gluster peer status'], null, true)
        salt.runSaltProcessStep(master, "I@glusterfs:server ${tgt_extra}", 'cmd.run', ['gluster volume status'], null, true)
    }

    // Ensure glusterfs clusters is ready
    if (salt.testTarget(master, "I@glusterfs:client ${tgt_extra}")) {
        salt.enforceState(master, "I@glusterfs:client ${tgt_extra}", 'glusterfs.client', true)
    }

    // Install galera
    if (salt.testTarget(master, "I@galera:master ${tgt_extra}") || salt.testTarget(master, 'I@galera:slave')) {
        withEnv(['ASK_ON_ERROR=false']){
            retry(2) {
                salt.enforceState(master, "I@galera:master ${tgt_extra}", 'galera', true)
            }
        }
        salt.enforceState(master, "I@galera:slave ${tgt_extra}", 'galera', true)

        // Check galera status
        salt.runSaltProcessStep(master, "I@galera:master ${tgt_extra}", 'mysql.status')
        salt.runSaltProcessStep(master, "I@galera:slave ${tgt_extra}", 'mysql.status')
    // If galera is not enabled check if we need to install mysql:server
    } else if (salt.testTarget(master, "I@mysql:server ${tgt_extra}")){
        salt.enforceState(master, "I@mysql:server ${tgt_extra}", 'mysql.server', true)
        if (salt.testTarget(master, "I@mysql:client ${tgt_extra}")){
            salt.enforceState(master, "I@mysql:client ${tgt_extra}", 'mysql.client', true)
        }
    }

    // Install docker
    if (salt.testTarget(master, "I@docker:host ${tgt_extra}")) {
        salt.enforceState(master, "I@docker:host ${tgt_extra}", 'docker.host')
        salt.cmdRun(master, "I@docker:host ${tgt_extra}", 'docker ps')
    }

    // Install keepalived
    if (salt.testTarget(master, "I@keepalived:cluster ${tgt_extra}")) {
        salt.enforceState(master, "I@keepalived:cluster and *01* ${tgt_extra}", 'keepalived', true)
        salt.enforceState(master, "I@keepalived:cluster ${tgt_extra}", 'keepalived', true)
    }

    // Install rabbitmq
    if (salt.testTarget(master, "I@rabbitmq:server ${tgt_extra}")) {
        withEnv(['ASK_ON_ERROR=false']){
            retry(2) {
                salt.enforceState(master, "I@rabbitmq:server ${tgt_extra}", 'rabbitmq', true)
            }
        }

        // Check the rabbitmq status
        salt.runSaltProcessStep(master, "I@rabbitmq:server ${tgt_extra}", 'cmd.run', ['rabbitmqctl cluster_status'])
    }

    // Install haproxy
    if (salt.testTarget(master, "I@haproxy:proxy ${tgt_extra}")) {
        salt.enforceState(master, "I@haproxy:proxy ${tgt_extra}", 'haproxy', true)
        salt.runSaltProcessStep(master, "I@haproxy:proxy ${tgt_extra}", 'service.status', ['haproxy'])
        salt.runSaltProcessStep(master, "I@haproxy:proxy ${tgt_extra}", 'service.restart', ['rsyslog'])
    }

    // Install memcached
    if (salt.testTarget(master, "I@memcached:server ${tgt_extra}")) {
        salt.enforceState(master, "I@memcached:server ${tgt_extra}", 'memcached', true)
    }

    // Install etcd
    if (salt.testTarget(master, "I@etcd:server ${tgt_extra}")) {
        salt.enforceState(master, "I@etcd:server ${tgt_extra}", 'etcd.server.service')
        salt.cmdRun(master, "I@etcd:server ${tgt_extra}", '. /var/lib/etcd/configenv && etcdctl cluster-health')
    }
}

def installOpenstackInfra(master, tgt_extra=null) {
    def orchestrate = new com.mirantis.mk.Orchestrate()

    // THIS FUNCTION IS LEGACY, PLEASE USE installInfra directly
    orchestrate.installInfra(master, tgt_extra)
}


def installOpenstackControl(master, tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    // Install horizon dashboard
    if (salt.testTarget(master, "I@horizon:server ${tgt_extra}")) {
        salt.enforceState(master, "I@horizon:server ${tgt_extra}", 'horizon', true)
    }
    if (salt.testTarget(master, "I@nginx:server ${tgt_extra}")) {
        salt.enforceState(master, "I@nginx:server ${tgt_extra}", 'salt.minion', true)
        salt.enforceState(master, "I@nginx:server ${tgt_extra}", 'nginx', true)
    }

    // setup keystone service
    if (salt.testTarget(master, "I@keystone:server ${tgt_extra}")) {
        //runSaltProcessStep(master, 'I@keystone:server', 'state.sls', ['keystone.server'], 1)
        salt.enforceState(master, "I@keystone:server and *01* ${tgt_extra}", 'keystone.server', true)
        salt.enforceState(master, "I@keystone:server ${tgt_extra}", 'keystone.server', true)
        // populate keystone services/tenants/roles/users

        // keystone:client must be called locally
        //salt.runSaltProcessStep(master, 'I@keystone:client', 'cmd.run', ['salt-call state.sls keystone.client'], null, true)
        salt.runSaltProcessStep(master, "I@keystone:server ${tgt_extra}", 'service.restart', ['apache2'])
        sleep(30)
    }
    if (salt.testTarget(master, "I@keystone:client ${tgt_extra}")) {
        salt.enforceState(master, "I@keystone:client and *01* ${tgt_extra}", 'keystone.client', true)
        salt.enforceState(master, "I@keystone:client ${tgt_extra}", 'keystone.client', true)
    }
    if (salt.testTarget(master, "I@keystone:server ${tgt_extra}")) {
        salt.runSaltProcessStep(master, "I@keystone:server ${tgt_extra}", 'cmd.run', ['. /root/keystonercv3; openstack service list'], null, true)
    }

    // Install glance
    if (salt.testTarget(master, "I@glance:server ${tgt_extra}")) {
        //runSaltProcessStep(master, 'I@glance:server', 'state.sls', ['glance.server'], 1)
        salt.enforceState(master, "I@glance:server and *01* ${tgt_extra}", 'glance.server', true)
       salt.enforceState(master, "I@glance:server ${tgt_extra}", 'glance.server', true)
    }

    // Check glance service
    if (salt.testTarget(master, "I@glance:server ${tgt_extra}")){
        salt.runSaltProcessStep(master, "I@keystone:server ${tgt_extra}", 'cmd.run', ['. /root/keystonerc; glance image-list'], null, true)
    }

    // Create glance resources
    if (salt.testTarget(master, "I@glance:client ${tgt_extra}")) {
        salt.enforceState(master, "I@glance:client ${tgt_extra}", 'glance.client', true)
    }

    // Install and check nova service
    if (salt.testTarget(master, "I@nova:controller ${tgt_extra}")) {
        //runSaltProcessStep(master, 'I@nova:controller', 'state.sls', ['nova'], 1)
        salt.enforceState(master, "I@nova:controller and *01* ${tgt_extra}", 'nova.controller', true)
        salt.enforceState(master, "I@nova:controller ${tgt_extra}", 'nova.controller', true)
        if (salt.testTarget(master, "I@keystone:server ${tgt_extra}")) {
            salt.runSaltProcessStep(master, "I@keystone:server ${tgt_extra}", 'cmd.run', ['. /root/keystonerc; nova service-list'], null, true)
        }
    }

    // Create nova resources
    if (salt.testTarget(master, "I@nova:client ${tgt_extra}")) {
        salt.enforceState(master, "I@nova:client ${tgt_extra}", 'nova.client', true)
    }

    // Install and check cinder service
    if (salt.testTarget(master, "I@cinder:controller ${tgt_extra}")) {
        //runSaltProcessStep(master, 'I@cinder:controller', 'state.sls', ['cinder'], 1)
        salt.enforceState(master, "I@cinder:controller and *01* ${tgt_extra}", 'cinder', true)
        salt.enforceState(master, "I@cinder:controller ${tgt_extra}", 'cinder', true)
        if (salt.testTarget(master, "I@keystone:server ${tgt_extra}")) {
            salt.runSaltProcessStep(master, "I@keystone:server ${tgt_extra}", 'cmd.run', ['. /root/keystonerc; cinder list'], null, true)
        }
    }

    // Install neutron service
    if (salt.testTarget(master, "I@neutron:server ${tgt_extra}")) {
        //runSaltProcessStep(master, 'I@neutron:server', 'state.sls', ['neutron'], 1)

        salt.enforceState(master, "I@neutron:server and *01* ${tgt_extra}", 'neutron.server', true)
        salt.enforceState(master, "I@neutron:server ${tgt_extra}", 'neutron.server', true)
        if (salt.testTarget(master, "I@keystone:server ${tgt_extra}")) {
            salt.runSaltProcessStep(master, "I@keystone:server ${tgt_extra}", 'cmd.run', ['. /root/keystonerc; neutron agent-list'], null, true)
        }
    }

    // Create neutron resources
    if (salt.testTarget(master, "I@neutron:client ${tgt_extra}")) {
        salt.enforceState(master, "I@neutron:client ${tgt_extra}", 'neutron.client', true)
    }

    // Install heat service
    if (salt.testTarget(master, "I@heat:server ${tgt_extra}")) {
        //runSaltProcessStep(master, 'I@heat:server', 'state.sls', ['heat'], 1)
        salt.enforceState(master, "I@heat:server and *01* ${tgt_extra}", 'heat', true)
        salt.enforceState(master, "I@heat:server ${tgt_extra}", 'heat', true)
        if (salt.testTarget(master, "I@keystone:server ${tgt_extra}")) {
            salt.runSaltProcessStep(master, "I@keystone:server ${tgt_extra}", 'cmd.run', ['. /root/keystonerc; heat resource-type-list'], null, true)
        }
    }

    // Restart nova api
    if (salt.testTarget(master, "I@nova:controller ${tgt_extra}")) {
        salt.runSaltProcessStep(master, "I@nova:controller ${tgt_extra}", 'service.restart', ['nova-api'])
    }

    // Install ironic service
    if (salt.testTarget(master, "I@ironic:api ${tgt_extra}")) {
        salt.enforceState(master, "I@ironic:api and *01* ${tgt_extra}", 'ironic.api', true)
        salt.enforceState(master, "I@ironic:api ${tgt_extra}", 'ironic.api', true)
    }

    // Install designate service
    if (salt.testTarget(master, "I@designate:server:enabled ${tgt_extra}")) {
        if (salt.testTarget(master, "I@designate:server:backend:bind9 ${tgt_extra}")) {
            salt.enforceState(master, "I@bind:server ${tgt_extra}", 'bind.server', true)
        }
        if (salt.testTarget(master, "I@designate:server:backend:pdns4 ${tgt_extra}")) {
            salt.enforceState(master, "I@powerdns:server ${tgt_extra}", 'powerdns.server', true)
        }
        salt.enforceState(master, "I@designate:server and *01* ${tgt_extra}", 'designate.server', true)
        salt.enforceState(master, "I@designate:server ${tgt_extra}", 'designate.server', true)
    }

    // Install octavia api service
    if (salt.testTarget(master, "I@octavia:api ${tgt_extra}")) {
        salt.enforceState(master, "I@octavia:api ${tgt_extra}", 'octavia', true)
    }

    // Install DogTag server service
    if (salt.testTarget(master, "I@dogtag:server ${tgt_extra}")) {
        salt.enforceState(master, "I@dogtag:server and *01* ${tgt_extra}", 'dogtag.server', true)
        salt.enforceState(master, "I@dogtag:server ${tgt_extra}", 'dogtag.server', true)
    }

    // Install barbican server service
    if (salt.testTarget(master, "I@barbican:server")) {
        salt.enforceState(master, "I@barbican:server and *01* ${tgt_extra}", 'barbican.server', true)
        salt.enforceState(master, "I@barbican:server ${tgt_extra}", 'barbican.server', true)
    }
    // Install barbican client
    if (salt.testTarget(master, "I@barbican:client ${tgt_extra}")) {
        salt.enforceState(master, "I@barbican:client ${tgt_extra}", 'barbican.client', true)
    }

    // Install ceilometer server
    if (salt.testTarget(master, "I@ceilometer:server ${tgt_extra}")) {
        salt.enforceState(master, "I@ceilometer:server ${tgt_extra}", 'ceilometer', true)
    }

    // Install aodh server
    if (salt.testTarget(master, "I@aodh:server ${tgt_extra}")) {
        salt.enforceState(master, "I@aodh:server ${tgt_extra}", 'aodh', true)
    }
}


def installIronicConductor(master, tgt_extra=null){
    def salt = new com.mirantis.mk.Salt()

    if (salt.testTarget(master, "I@ironic:conductor ${tgt_extra}")) {
        salt.enforceState(master, "I@ironic:conductor ${tgt_extra}", 'ironic.conductor', true)
        salt.enforceState(master, "I@ironic:conductor ${tgt_extra}", 'apache', true)
    }
    if (salt.testTarget(master, "I@tftpd_hpa:server ${tgt_extra}")) {
        salt.enforceState(master, "I@tftpd_hpa:server ${tgt_extra}", 'tftpd_hpa', true)
    }

    if (salt.testTarget(master, "I@nova:compute ${tgt_extra}")) {
        salt.runSaltProcessStep(master, "I@nova:compute ${tgt_extra}", 'service.restart', ['nova-compute'])
    }

    if (salt.testTarget(master, "I@baremetal_simulator:enabled ${tgt_extra}")) {
        salt.enforceState(master, "I@baremetal_simulator:enabled ${tgt_extra}", 'baremetal_simulator', true)
    }
    if (salt.testTarget(master, "I@ironic:client ${tgt_extra}")) {
        salt.enforceState(master, "I@ironic:client ${tgt_extra}", 'ironic.client', true)
    }
}



def installOpenstackNetwork(master, physical = "false", tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    salt.runSaltProcessStep(master, "I@neutron:gateway ${tgt_extra}", 'state.apply', [], null, true)

    // install octavia manager services
    if (salt.testTarget(master, "I@octavia:manager ${tgt_extra}")) {
        salt.runSaltProcessStep(master, "I@salt:master ${tgt_extra}", 'mine.update', ['*'], null, true)
        salt.enforceState(master, "I@octavia:manager ${tgt_extra}", 'octavia', true)
        salt.enforceState(master, "I@octavia:manager ${tgt_extra}", 'salt.minion.ca', true)
        salt.enforceState(master, "I@octavia:manager ${tgt_extra}", 'salt.minion.cert', true)
    }
}


def installOpenstackCompute(master, tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    // Configure compute nodes
    retry(2) {
        salt.runSaltProcessStep(master, "I@nova:compute ${tgt_extra}", 'state.highstate', ['exclude=opencontrail.client'], null, true)
    }
}


def installContrailNetwork(master, tgt_extra=null) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()


    // Install opencontrail database services
    //runSaltProcessStep(master, 'I@opencontrail:database', 'state.sls', ['opencontrail.database'], 1)
    if(env["ASK_ON_ERROR"] && env["ASK_ON_ERROR"] == "true"){
        salt.enforceState(master, "I@opencontrail:database and *01* ${tgt_extra}", 'opencontrail.database', true)
        salt.enforceState(master, "I@opencontrail:database ${tgt_extra}", 'opencontrail.database', true)
    }else{
        try {
            salt.enforceState(master, "I@opencontrail:database and *01* ${tgt_extra}", 'opencontrail.database', true)
        } catch (Exception e) {
            common.warningMsg('Exception in state opencontrail.database on I@opencontrail:database and *01*')
        }
        try {
            salt.enforceState(master, "I@opencontrail:database and *01* ${tgt_extra}", 'opencontrail.database', true)
        } catch (Exception e) {
            common.warningMsg('Exception in state opencontrail.database on I@opencontrail:database')
        }
    }

    // Install opencontrail control services
    //runSaltProcessStep(master, 'I@opencontrail:control', 'state.sls', ['opencontrail'], 1)
    salt.runSaltProcessStep(master, "I@opencontrail:control and *01* ${tgt_extra}", 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
    salt.runSaltProcessStep(master, "I@opencontrail:control ${tgt_extra}", 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
    salt.runSaltProcessStep(master, "I@opencontrail:collector ${tgt_extra}", 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])

    if (salt.testTarget(master, "I@docker:client and I@opencontrail:control ${tgt_extra}")) {
        salt.enforceState(master, "I@opencontrail:control or I@opencontrail:collector ${tgt_extra}", 'docker.client', true)
    }
}


def installContrailCompute(master, tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    // Configure compute nodes
    // Provision opencontrail control services
    salt.enforceState(master, "I@opencontrail:database:id:1 ${tgt_extra}", 'opencontrail.client', true)
    // Provision opencontrail virtual routers

    // Generate script /usr/lib/contrail/if-vhost0 for up vhost0
    if(env["ASK_ON_ERROR"] && env["ASK_ON_ERROR"] == "true"){
        salt.runSaltProcessStep(master, "I@opencontrail:compute ${tgt_extra}", 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
    }else{
        try {
            salt.runSaltProcessStep(master, "I@opencontrail:compute ${tgt_extra}", 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
        } catch (Exception e) {
            common.warningMsg('Exception in state opencontrail on I@opencontrail:compute')
        }
    }

    salt.runSaltProcessStep(master, 'I@nova:compute', 'cmd.run', ['exec 0>&-; exec 1>&-; exec 2>&-; nohup bash -c "ip link | grep vhost && echo no_reboot || sleep 5 && reboot & "'], null, true)

    if (salt.testTarget(master, 'I@opencontrail:compute')) {
        sleep(300)
        salt.enforceState(master, 'I@opencontrail:compute', 'opencontrail.client', true)
        salt.enforceState(master, 'I@opencontrail:compute', 'opencontrail', true)
    }
}


def installKubernetesInfra(master, tgt_extra=null) {
    def orchestrate = new com.mirantis.mk.Orchestrate()
    // THIS FUNCTION IS LEGACY, PLEASE USE installInfra directly
    orchestrate.installInfra(master, tgt_extra)
}


def installKubernetesControl(master, tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    // Install Kubernetes pool and Calico
    salt.enforceState(master, "I@kubernetes:master ${tgt_extra}", 'kubernetes.master.kube-addons')
    salt.enforceState(master, "I@kubernetes:pool ${tgt_extra}", 'kubernetes.pool')

    if (salt.testTarget(master, "I@etcd:server:setup ${tgt_extra}")) {
        // Setup etcd server
        salt.enforceState(master, "I@kubernetes:master and *01* ${tgt_extra}", 'etcd.server.setup')
    }

    // Run k8s without master.setup
    salt.runSaltProcessStep(master, "I@kubernetes:master ${tgt_extra}", 'state.sls', ['kubernetes', 'exclude=kubernetes.master.setup'])

    // Run k8s master setup
    salt.enforceState(master, "I@kubernetes:master and *01* ${tgt_extra}", 'kubernetes.master.setup')

    // Restart kubelet
    salt.runSaltProcessStep(master, "I@kubernetes:pool ${tgt_extra}", 'service.restart', ['kubelet'])
}


def installKubernetesCompute(master, tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    // Refresh minion's pillar data
    salt.runSaltProcessStep(master, '*', 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, '*', 'saltutil.sync_all', [], null, true)

    // Bootstrap all nodes
    salt.enforceState(master, "I@kubernetes:pool ${tgt_extra}", 'linux')
    salt.enforceState(master, "I@kubernetes:pool ${tgt_extra}", 'salt.minion')
    salt.enforceState(master, "I@kubernetes:pool ${tgt_extra}", ['openssh', 'ntp'])

    // Create and distribute SSL certificates for services using salt state
    salt.enforceState(master, "I@kubernetes:pool ${tgt_extra}", 'salt.minion.cert')

    // Install docker
    salt.enforceState(master, "I@docker:host ${tgt_extra}", 'docker.host')

    // Install Kubernetes and Calico
    salt.enforceState(master, "I@kubernetes:pool ${tgt_extra}", 'kubernetes.pool')

    // Install Tiller and all configured releases
    if (salt.testTarget(master, "I@helm:client ${tgt_extra}")) {
        salt.enforceState(master, "I@helm:client ${tgt_extra}", 'helm')
    }
}


def installDockerSwarm(master) {
    def salt = new com.mirantis.mk.Salt()

    //Install and Configure Docker
    salt.enforceState(master, 'I@docker:swarm', 'docker.host')
    salt.enforceState(master, 'I@docker:swarm:role:master', 'docker.swarm', true)
    salt.enforceState(master, 'I@docker:swarm', 'salt.minion.grains', true)
    salt.runSaltProcessStep(master, 'I@docker:swarm', 'mine.update', [], null, true)
    salt.runSaltProcessStep(master, 'I@docker:swarm', 'saltutil.refresh_modules', [], null, true)
    sleep(5)
    salt.enforceState(master, 'I@docker:swarm:role:master', 'docker.swarm', true)
    salt.enforceState(master, 'I@docker:swarm:role:manager', 'docker.swarm', true)
    salt.cmdRun(master, 'I@docker:swarm:role:master', 'docker node ls', true)
}


def installCicd(master) {
    def salt = new com.mirantis.mk.Salt()

    //Install and Configure Docker
    salt.runSaltProcessStep(master, 'I@jenkins:client or I@gerrit:client', 'saltutil.refresh_pillar', [], null, true)
    salt.runSaltProcessStep(master, 'I@jenkins:client or I@gerrit:client', 'saltutil.sync_all', [], null, true)

    if (salt.testTarget(master, 'I@aptly:publisher')) {
        salt.enforceState(master, 'I@aptly:publisher', 'aptly.publisher',true, null, false, -1, 2)
    }

    salt.enforceState(master, 'I@docker:swarm:role:master and I@jenkins:client', 'docker.client', true, true, null, false, -1, 2)
    sleep(500)

    if (salt.testTarget(master, 'I@aptly:server')) {
        salt.enforceState(master, 'I@aptly:server', 'aptly', true, true, null, false, -1, 2)
    }

    if (salt.testTarget(master, 'I@openldap:client')) {
        salt.enforceState(master, 'I@openldap:client', 'openldap', true, true, null, false, -1, 2)
    }

    if (salt.testTarget(master, 'I@python:environment')) {
        salt.enforceState(master, 'I@python:environment', 'python', true)
    }

    withEnv(['ASK_ON_ERROR=false']){
        retry(2){
            try{
                salt.enforceState(master, 'I@gerrit:client', 'gerrit', true)
            }catch(e){
                salt.runSaltProcessStep(master, 'I@gerrit:client', 'saltutil.refresh_pillar', [], null, true)
                salt.runSaltProcessStep(master, 'I@gerrit:client', 'saltutil.sync_all', [], null, true)
                throw e //rethrow for retry handler
            }
        }
        retry(2){
            try{
                salt.enforceState(master, 'I@jenkins:client', 'jenkins', true)
            }catch(e){
                salt.runSaltProcessStep(master, 'I@jenkins:client', 'saltutil.refresh_pillar', [], null, true)
                salt.runSaltProcessStep(master, 'I@jenkins:client', 'saltutil.sync_all', [], null, true)
                throw e //rethrow for retry handler
            }
        }
    }
}


def installStacklight(master) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()

    // Install core services for K8S environments:
    // HAProxy, Nginx and lusterFS clients
    // In case of OpenStack, those are already installed
    if (common.checkContains('STACK_INSTALL', 'k8s')) {
        salt.enforceState(master, 'I@haproxy:proxy', 'haproxy')
        salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])

        if (salt.testTarget(master, 'I@nginx:server')) {
            salt.enforceState(master, 'I@nginx:server', 'nginx', true)
        }

        if (salt.testTarget(master, 'I@glusterfs:client')) {
            salt.enforceState(master, 'I@glusterfs:client', 'glusterfs.client', true)
        }
    }

    // Launch containers
    salt.enforceState(master, 'I@docker:swarm:role:master and I@prometheus:server', 'docker.client', true)
    salt.runSaltProcessStep(master, 'I@docker:swarm and I@prometheus:server', 'dockerng.ps', [], null, true)

    //Install Telegraf
    salt.enforceState(master, 'I@telegraf:agent or I@telegraf:remote_agent', 'telegraf', true)

    // Install Prometheus exporters
    if (salt.testTarget(master, 'I@prometheus:exporters')) {
        salt.enforceState(master, 'I@prometheus:exporters', 'prometheus', true)
    }

    //Install Elasticsearch and Kibana
    salt.enforceState(master, '*01* and  I@elasticsearch:server', 'elasticsearch.server', true)
    salt.enforceState(master, 'I@elasticsearch:server', 'elasticsearch.server', true)
    salt.enforceState(master, '*01* and I@kibana:server', 'kibana.server', true)
    salt.enforceState(master, 'I@kibana:server', 'kibana.server', true)
    salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client', true)
    salt.enforceState(master, 'I@kibana:client', 'kibana.client', true)
    salt.enforceState(master, '*01* and I@influxdb:server', 'influxdb', true)
    salt.enforceState(master, 'I@influxdb:server', 'influxdb', true)

    salt.enforceState(master, 'I@heka:log_collector', 'heka.log_collector')

    // Install heka ceilometer collector
    if (salt.testTarget(master, 'I@heka:ceilometer_collector:enabled')) {
        salt.enforceState(master, 'I@heka:ceilometer_collector:enabled', 'heka.ceilometer_collector', true)
        salt.runSaltProcessStep(master, 'I@heka:ceilometer_collector:enabled', 'service.restart', ['ceilometer_collector'], null, true)
    }

    // Install galera
    if (common.checkContains('STACK_INSTALL', 'k8s')) {
        withEnv(['ASK_ON_ERROR=false']){
            retry(2) {
                salt.enforceState(master, 'I@galera:master', 'galera', true)
            }
        }
        salt.enforceState(master, 'I@galera:slave', 'galera', true)

        // Check galera status
        salt.runSaltProcessStep(master, 'I@galera:master', 'mysql.status')
        salt.runSaltProcessStep(master, 'I@galera:slave', 'mysql.status')
    }

    //Collect Grains
    salt.enforceState(master, 'I@salt:minion', 'salt.minion.grains', true)
    salt.runSaltProcessStep(master, 'I@salt:minion', 'saltutil.refresh_modules', [], null, true)
    salt.runSaltProcessStep(master, 'I@salt:minion', 'mine.update', [], null, true)
    sleep(5)

    //Configure services in Docker Swarm
    if (common.checkContains('STACK_INSTALL', 'k8s')) {
            salt.enforceState(master, 'I@docker:swarm and I@prometheus:server', 'prometheus', true, false)
    }
    else {
        salt.enforceState(master, 'I@docker:swarm and I@prometheus:server', ['prometheus', 'heka.remote_collector'], true, false)
    }

    //Configure Grafana
    def pillar = salt.getPillar(master, 'ctl01*', '_param:stacklight_monitor_address')
    common.prettyPrint(pillar)

    def stacklight_vip
    if(!pillar['return'].isEmpty()) {
        stacklight_vip = pillar['return'][0].values()[0]
    } else {
        common.errorMsg('[ERROR] Stacklight VIP address could not be retrieved')
    }

    common.infoMsg("Waiting for service on http://${stacklight_vip}:15013/ to start")
    sleep(120)
    salt.enforceState(master, 'I@grafana:client', 'grafana.client', true)
}

def installStacklightv1Control(master) {
    def salt = new com.mirantis.mk.Salt()

    // infra install
    // Install the StackLight backends
    salt.enforceState(master, '*01* and  I@elasticsearch:server', 'elasticsearch.server', true)
    salt.enforceState(master, 'I@elasticsearch:server', 'elasticsearch.server', true)

    salt.enforceState(master, '*01* and I@influxdb:server', 'influxdb', true)
    salt.enforceState(master, 'I@influxdb:server', 'influxdb', true)

    salt.enforceState(master, '*01* and I@kibana:server', 'kibana.server', true)
    salt.enforceState(master, 'I@kibana:server', 'kibana.server', true)

    salt.enforceState(master, '*01* and I@grafana:server','grafana.server', true)
    salt.enforceState(master, 'I@grafana:server','grafana.server', true)

    def alarming_service_pillar = salt.getPillar(master, 'mon*01*', '_param:alarming_service')
    def alarming_service = alarming_service_pillar['return'][0].values()[0]

    switch (alarming_service) {
        case 'sensu':
            // Update Sensu
            salt.enforceState(master, 'I@sensu:server and I@rabbitmq:server', 'rabbitmq', true)
            salt.enforceState(master, 'I@redis:cluster:role:master', 'redis', true)
            salt.enforceState(master, 'I@redis:server', 'redis', true)
            salt.enforceState(master, 'I@sensu:server', 'sensu', true)
        default:
            // Update Nagios
            salt.enforceState(master, 'I@nagios:server', 'nagios.server', true)
            // Stop the Nagios service because the package starts it by default and it will
            // started later only on the node holding the VIP address
            salt.runSaltProcessStep(master, 'I@nagios:server', 'service.stop', ['nagios3'], null, true)
    }

    salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client.service', true)
    salt.enforceState(master, 'I@kibana:client', 'kibana.client', true)

    sleep(10)
}

def installStacklightv1Client(master) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    salt.runSaltProcessStep(master, 'I@elasticsearch:client', 'cmd.run', ['salt-call state.sls elasticsearch.client'], null, true)
    // salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client', true)
    salt.runSaltProcessStep(master, 'I@kibana:client', 'cmd.run', ['salt-call state.sls kibana.client'], null, true)
    // salt.enforceState(master, 'I@kibana:client', 'kibana.client', true)

    // Install collectd, heka and sensu services on the nodes, this will also
    // generate the metadata that goes into the grains and eventually into Salt Mine
    salt.enforceState(master, '*', 'collectd', true)
    salt.enforceState(master, '*', 'salt.minion', true)
    salt.enforceState(master, '*', 'heka', true)

    // Gather the Grafana metadata as grains
    salt.enforceState(master, 'I@grafana:collector', 'grafana.collector', true)

    // Update Salt Mine
    salt.enforceState(master, '*', 'salt.minion.grains', true)
    salt.runSaltProcessStep(master, '*', 'saltutil.refresh_modules', [], null, true)
    salt.runSaltProcessStep(master, '*', 'mine.update', [], null, true)

    sleep(5)

    // Update Heka
    salt.enforceState(master, 'I@heka:aggregator:enabled:True or I@heka:remote_collector:enabled:True', 'heka', true)

    // Update collectd
    salt.enforceState(master, 'I@collectd:remote_client:enabled:True', 'collectd', true)

    def alarming_service_pillar = salt.getPillar(master, 'mon*01*', '_param:alarming_service')
    def alarming_service = alarming_service_pillar['return'][0].values()[0]

    switch (alarming_service) {
        case 'sensu':
            // Update Sensu
            // TODO for stacklight team, should be fixed in model
            salt.enforceState(master, 'I@sensu:client', 'sensu', true)
        default:
            break
            // Default is nagios, and was enforced in installStacklightControl()
    }

    salt.runSaltProcessStep(master, 'I@grafana:client and *01*', 'cmd.run', ['salt-call state.sls grafana.client'], null, true)
    // salt.enforceState(master, 'I@grafana:client and *01*', 'grafana.client', true)

    // Finalize the configuration of Grafana (add the dashboards...)
    salt.enforceState(master, 'I@grafana:client and *01*', 'grafana.client', true)
    salt.enforceState(master, 'I@grafana:client and *02*', 'grafana.client', true)
    salt.enforceState(master, 'I@grafana:client and *03*', 'grafana.client', true)
    // nw salt -C 'I@grafana:client' --async service.restart salt-minion; sleep 10

    // Get the StackLight monitoring VIP addres
    //vip=$(salt-call pillar.data _param:stacklight_monitor_address --out key|grep _param: |awk '{print $2}')
    //vip=${vip:=172.16.10.253}
    def pillar = salt.getPillar(master, 'ctl01*', '_param:stacklight_monitor_address')
    common.prettyPrint(pillar)
    def stacklight_vip = pillar['return'][0].values()[0]

    if (stacklight_vip) {
        // (re)Start manually the services that are bound to the monitoring VIP
        common.infoMsg("restart services on node with IP: ${stacklight_vip}")
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['remote_collectd'], null, true)
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['remote_collector'], null, true)
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['aggregator'], null, true)
        salt.runSaltProcessStep(master, "G@ipv4:${stacklight_vip}", 'service.restart', ['nagios3'], null, true)
    } else {
        throw new Exception("Missing stacklight_vip")
    }
}


//
// Ceph
//

def installCephMon(master, target='I@ceph:mon', tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    salt.enforceState(master, "I@ceph:common ${tgt_extra}", 'salt.minion.grains', true)

    // generate keyrings
    if (salt.testTarget(master, "I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ${tgt_extra}")) {
        salt.enforceState(master, "I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ${tgt_extra}", 'ceph.mon', true)
        salt.runSaltProcessStep(master, "I@ceph:mon ${tgt_extra}", 'saltutil.sync_grains', [], null, true)
        salt.runSaltProcessStep(master, "I@ceph:mon:keyring:mon or I@ceph:common:keyring:admin ${tgt_extra}", 'mine.update', [], null, true)
        sleep(5)
    }
    // install Ceph Mons
    salt.enforceState(master, target, 'ceph.mon', true)
    if (salt.testTarget(master, "I@ceph:mgr ${tgt_extra}")) {
        salt.enforceState(master, "I@ceph:mgr ${tgt_extra}", 'ceph.mgr', true)
    }
}

def installCephOsd(master, target='I@ceph:osd', setup=true, tgt_extra=null) {
    def salt = new com.mirantis.mk.Salt()

    // install Ceph OSDs
    salt.enforceState(master, target, 'ceph.osd', true)
    salt.runSaltProcessStep(master, "I@ceph:osd ${tgt_extra}", 'saltutil.sync_grains', [], null, true)
    salt.enforceState(master, target, 'ceph.osd.custom', true)
    salt.runSaltProcessStep(master, "I@ceph:osd ${tgt_extra}", 'saltutil.sync_grains', [], null, true)
    salt.runSaltProcessStep(master, "I@ceph:osd ${tgt_extra}", 'mine.update', [], null, true)

    // setup pools, keyrings and maybe crush
    if (salt.testTarget(master, "I@ceph:setup ${tgt_extra}") && setup) {
        sleep(5)
        salt.enforceState(master, "I@ceph:setup ${tgt_extra}", 'ceph.setup', true)
    }
}

def installCephClient(master) {
    def salt = new com.mirantis.mk.Salt()

    // install Ceph Radosgw
    if (salt.testTarget(master, 'I@ceph:radosgw')) {
        salt.runSaltProcessStep(master, 'I@ceph:radosgw', 'saltutil.sync_grains', [], null, true)
        salt.enforceState(master, 'I@ceph:radosgw', 'ceph.radosgw', true)
    }
    // setup Keystone service and endpoints for swift or / and S3
    if (salt.testTarget(master, 'I@keystone:client')) {
        salt.enforceState(master, 'I@keystone:client', 'keystone.client', true)
    }
}

def connectCeph(master) {
    def salt = new com.mirantis.mk.Salt()

    // connect Ceph to the env
    if (salt.testTarget(master, 'I@ceph:common and I@glance:server')) {
        salt.enforceState(master, 'I@ceph:common and I@glance:server', ['ceph.common', 'ceph.setup.keyring', 'glance'], true)
        salt.runSaltProcessStep(master, 'I@ceph:common and I@glance:server', 'service.restart', ['glance-api', 'glance-glare', 'glance-registry'])
    }
    if (salt.testTarget(master, 'I@ceph:common and I@cinder:controller')) {
        salt.enforceState(master, 'I@ceph:common and I@cinder:controller', ['ceph.common', 'ceph.setup.keyring', 'cinder'], true)
    }
    if (salt.testTarget(master, 'I@ceph:common and I@nova:compute')) {
        salt.enforceState(master, 'I@ceph:common and I@nova:compute', ['ceph.common', 'ceph.setup.keyring'], true)
        salt.runSaltProcessStep(master, 'I@ceph:common and I@nova:compute', 'saltutil.sync_grains', [], null, true)
        salt.enforceState(master, 'I@ceph:common and I@nova:compute', ['nova'], true)
    }
}

def installOssInfra(master) {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  if (!common.checkContains('STACK_INSTALL', 'k8s') || !common.checkContains('STACK_INSTALL', 'openstack')) {
    def orchestrate = new com.mirantis.mk.Orchestrate()
    orchestrate.installInfra(master)
  }

  if (salt.testTarget(master, 'I@devops_portal:config')) {
    salt.enforceState(master, 'I@devops_portal:config', 'devops_portal.config', true)
    salt.enforceState(master, 'I@rundeck:client', ['linux.system.user', 'openssh'], true)
    salt.enforceState(master, 'I@rundeck:server', 'rundeck.server', true)
  }
}

def installOss(master) {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  //Get oss VIP address
  def pillar = salt.getPillar(master, 'cfg01*', '_param:stacklight_monitor_address')
  common.prettyPrint(pillar)

  def oss_vip
  if(!pillar['return'].isEmpty()) {
      oss_vip = pillar['return'][0].values()[0]
  } else {
      common.errorMsg('[ERROR] Oss VIP address could not be retrieved')
  }

  // Postgres client - initialize OSS services databases
  timeout(120){
    common.infoMsg("Waiting for postgresql database to come up..")
    salt.cmdRun(master, 'I@postgresql:client', 'while true; do if docker service logs postgresql_postgresql-db | grep "ready to accept"; then break; else sleep 5; fi; done')
  }
  // XXX: first run usually fails on some inserts, but we need to create databases at first
  salt.enforceState(master, 'I@postgresql:client', 'postgresql.client', true, false)

  // Setup postgres database with integration between
  // Pushkin notification service and Security Monkey security audit service
  timeout(10) {
    common.infoMsg("Waiting for Pushkin to come up..")
    salt.cmdRun(master, 'I@postgresql:client', "while true; do curl -sf ${oss_vip}:8887/apps >/dev/null && break; done")
  }
  salt.enforceState(master, 'I@postgresql:client', 'postgresql.client', true)

  // Rundeck
  timeout(10) {
    common.infoMsg("Waiting for Rundeck to come up..")
    salt.cmdRun(master, 'I@rundeck:client', "while true; do curl -sf ${oss_vip}:4440 >/dev/null && break; done")
  }
  salt.enforceState(master, 'I@rundeck:client', 'rundeck.client', true)

  // Elasticsearch
  pillar = salt.getPillar(master, 'I@elasticsearch:client', 'elasticsearch:client:server:host')
  def elasticsearch_vip
  if(!pillar['return'].isEmpty()) {
    elasticsearch_vip = pillar['return'][0].values()[0]
  } else {
    common.errorMsg('[ERROR] Elasticsearch VIP address could not be retrieved')
  }

  timeout(10) {
    common.infoMsg('Waiting for Elasticsearch to come up..')
    salt.cmdRun(master, 'I@elasticsearch:client', "while true; do curl -sf ${elasticsearch_vip}:9200 >/dev/null && break; done")
  }
  salt.enforceState(master, 'I@elasticsearch:client', 'elasticsearch.client', true)
}
