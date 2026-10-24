"""Explicit supported Fabric targets; Docker and Velocity retain the 26.2 path."""
from release_version import properties


def target(minecraft='26.2'):
    props = properties()
    if minecraft not in ('26.2', '26.3'):
        raise ValueError(f'Unsupported Minecraft target: {minecraft}')
    api = props['fabric_version_26_3' if minecraft == '26.3' else 'fabric_version']
    return dict(minecraft=minecraft, fabric_version=api,
                fabric_minimum='>=' + api.split('+')[0],
                loader_minimum='>=' + props['loader_version'])
